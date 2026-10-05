/* See include/rescue.h.
 *
 * Async-signal-safe by construction: raw syscalls, fixed storage, no
 * allocation, no libc. The only shared state is the slot table, whose
 * ownership word is claimed with a CAS and otherwise written by the
 * owning thread alone.
 *
 * Slot lifetime. The wrapper claims a slot keyed by the trapping
 * thread's tid, and releases it when the handler returns. Two ways a
 * slot could leak:
 *   - a handler that never returns. `handle_exit` is the one such
 *     handler, so the wrapper skips the claim for it entirely; the
 *     exec commit path calls tawcroot_rescue_restore() and then
 *     replaces the process image (fresh BSS, whole table gone).
 *   - fork. `clone` is not in the trap set, so a fork never happens
 *     *inside* the wrapper; a multi-threaded guest that forks while a
 *     sibling thread is mid-trap leaves that sibling's slot claimed in
 *     the child. Bounded (one per concurrently-trapping sibling per
 *     fork) and self-healing on tid reuse, since a claim first
 *     reclaims any slot already carrying our own tid. A full table
 *     just means no rescue for that call.
 */

#include <stddef.h>
#include <stdint.h>

#include <sys/stat.h>

#include "dispatch.h"
#include "errno_neg.h"
#include "identity.h"
#include "path.h"
#include "raw_sys.h"
#include "rescue.h"
#include "sysnr.h"
#include "tawc_uapi.h"

/* Concurrently-trapping threads that can be rescued at once. Beyond
 * this the rescue silently switches off (the guest sees the original
 * EACCES), which is the pre-rescue behaviour. */
#define RESCUE_SLOTS       64
/* linkat/renameat translate two paths; nothing translates more. */
#define RESCUE_MAX_ROUTES   2
/* Routes longer than this are not rescued. Distro paths sit far below
 * it; the cap is what keeps the table out of megabyte territory. */
#define RESCUE_PATH_MAX  1024
/* Widened inodes per call: path components across both routes, plus
 * their base fds. */
#define RESCUE_MAX_SAVED   40

struct rescue_route {
	int    base_fd;
	size_t len;
	char   path[RESCUE_PATH_MAX];
};

/* One widened inode, addressed the way we found it: a prefix of a
 * recorded route. `prefix_len` 0 means the route's base fd itself. */
struct rescue_saved {
	unsigned short route;
	unsigned short prefix_len;
	unsigned int   mode;        /* the mode bits to put back */
};

struct rescue_slot {
	int n_routes;
	int n_saved;
	int overflow;   /* something didn't fit — do not rescue this call */
	int frozen;     /* inside the retry: stop recording routes */
	struct rescue_route routes[RESCUE_MAX_ROUTES];
	struct rescue_saved saved[RESCUE_MAX_SAVED];
};

/* Ownership words live apart from the payload so the lookup scan walks
 * a handful of cache lines instead of one per slot. 0 = free. */
static long               g_slot_tid[RESCUE_SLOTS];
static struct rescue_slot g_slots[RESCUE_SLOTS];
static int                g_claimed;   /* fast "nobody is rescuing" gate */

static long current_tid(void)
{
	long t = TAWC_RAW(TAWC_SYS_gettid, 0, 0, 0, 0, 0, 0);
	return t > 0 ? t : 0;
}

static int slot_claim(long tid)
{
	if (tid == 0) return -1;
	for (int i = 0; i < RESCUE_SLOTS; i++) {
		/* Reclaim our own stale slot first (a tid whose previous
		 * owner died without returning to the wrapper), then take
		 * a free one. */
		long expected = __atomic_load_n(&g_slot_tid[i], __ATOMIC_ACQUIRE);
		if (expected != tid) {
			expected = 0;
			if (!__atomic_compare_exchange_n(&g_slot_tid[i],
							 &expected, tid, 0,
							 __ATOMIC_ACQUIRE,
							 __ATOMIC_RELAXED))
				continue;
			__atomic_fetch_add(&g_claimed, 1, __ATOMIC_RELAXED);
		}
		g_slots[i].n_routes = 0;
		g_slots[i].n_saved  = 0;
		g_slots[i].overflow = 0;
		g_slots[i].frozen   = 0;
		return i;
	}
	return -1;
}

static void slot_release(int idx)
{
	/* Free the slot BEFORE dropping the count. The other order can
	 * flash g_claimed to 0 while another thread still holds a slot,
	 * and that thread's slot_find() fast-path would then silently
	 * drop its route. */
	__atomic_store_n(&g_slot_tid[idx], 0, __ATOMIC_RELEASE);
	__atomic_fetch_sub(&g_claimed, 1, __ATOMIC_RELAXED);
}

static struct rescue_slot *slot_find(void)
{
	if (__atomic_load_n(&g_claimed, __ATOMIC_RELAXED) == 0) return 0;
	long tid = current_tid();
	if (tid == 0) return 0;
	for (int i = 0; i < RESCUE_SLOTS; i++) {
		if (__atomic_load_n(&g_slot_tid[i], __ATOMIC_ACQUIRE) == tid)
			return &g_slots[i];
	}
	return 0;
}

void tawcroot_rescue_note(int base_fd, const char *path)
{
	struct rescue_slot *s = slot_find();
	if (!s || s->frozen || s->overflow || !path) return;
	if (s->n_routes >= RESCUE_MAX_ROUTES) { s->overflow = 1; return; }

	struct rescue_route *r = &s->routes[s->n_routes];
	size_t n = 0;
	while (path[n] && n < RESCUE_PATH_MAX - 1) { r->path[n] = path[n]; n++; }
	if (path[n]) { s->overflow = 1; return; }
	r->path[n]  = 0;
	r->len      = n;
	r->base_fd  = base_fd;
	s->n_routes++;
}

/* Syscalls whose kernel implementation touches the LEAF's own
 * permissions, as opposed to only needing search/write on its parent.
 * Everything else (unlink, mkdir, mknod, symlink, link, rename, the
 * stat family) gets parent widening only: widening their leaf is
 * wasted work, and for rename it would leak the wide mode to the
 * destination name, where the by-path restore no longer finds it.
 *
 * Never a reason to add x: CAP_DAC_OVERRIDE does not grant exec on a
 * file with no execute bit either, and faccessat(X_OK) must stay
 * honest. We only ever add u+rw to a regular leaf (directories get
 * u+rwx wherever they appear — a directory's x IS its search bit). */
static int rescue_needs_leaf(long nr)
{
	switch (nr) {
	case TAWC_SYS_openat:
	case TAWC_SYS_truncate:
	case TAWC_SYS_faccessat:
	case TAWC_SYS_faccessat2:
	case TAWC_SYS_chdir:
	case TAWC_SYS_chroot:
	case TAWC_SYS_execve:
	case TAWC_SYS_execveat:
	case TAWC_SYS_utimensat:
	case TAWC_SYS_setxattr:
	case TAWC_SYS_lsetxattr:
	case TAWC_SYS_getxattr:
	case TAWC_SYS_lgetxattr:
	case TAWC_SYS_listxattr:
	case TAWC_SYS_llistxattr:
	case TAWC_SYS_removexattr:
	case TAWC_SYS_lremovexattr:
#if defined(__x86_64__)
	case TAWC_SYS_open:
	case TAWC_SYS_creat:
	case TAWC_SYS_access:
	case TAWC_SYS_utime:
	case TAWC_SYS_utimes:
	case TAWC_SYS_futimesat:
#endif
		return 1;
	default:
		return 0;
	}
}

/* Widen one inode reached as `path` relative to `fd`. Returns 1 when
 * the inode is owned by the app uid (whether or not anything needed
 * widening) — that is what licenses the retry.
 *
 * Never chmods through a symlink: leaves are symlinks under the
 * NOFOLLOW ops and every emulated hardlink name is a
 * `tawcroot:link:<token>` symlink, while fchmodat has no
 * AT_SYMLINK_NOFOLLOW. We stat NOFOLLOW and touch nothing that isn't a
 * directory or a regular file. */
static int widen_one(struct rescue_slot *s, int ri, size_t prefix_len,
		     int fd, const char *path, unsigned int uid, int is_leaf)
{
	struct stat st;
	long sr = TAWC_RAW(TAWC_SYS_fstatat, fd, (long)path, (long)&st,
			   AT_SYMLINK_NOFOLLOW, 0, 0);
	if (sr < 0) return 0;
	if ((unsigned int)st.st_uid != uid) return 0;

	unsigned int mode = st.st_mode & 07777u;
	unsigned int want;
	if (S_ISDIR(st.st_mode))
		want = mode | S_IRUSR | S_IWUSR | S_IXUSR;
	else if (S_ISREG(st.st_mode) && is_leaf)
		want = mode | S_IRUSR | S_IWUSR;
	else
		return 1;
	if (want == mode) return 1;

	if (s->n_saved >= RESCUE_MAX_SAVED) { s->overflow = 1; return 1; }
	if (TAWC_RAW(TAWC_SYS_fchmodat, fd, (long)path, want, 0, 0, 0) < 0)
		return 1;
	s->saved[s->n_saved].route      = (unsigned short)ri;
	s->saved[s->n_saved].prefix_len = (unsigned short)prefix_len;
	s->saved[s->n_saved].mode       = mode;
	s->n_saved++;
	return 1;
}

/* Walk a recorded route: the base fd, then each path prefix. Returns 1
 * when any component is app-owned. */
static int widen_route(struct rescue_slot *s, int ri, unsigned int uid,
		       int want_leaf)
{
	struct rescue_route *r = &s->routes[ri];
	/* O_PATH base fds make fchmod EBADF; go through the dirfd. */
	int found = widen_one(s, ri, 0, r->base_fd, ".", uid, 0);

	size_t i = 0;
	while (i < r->len && !s->overflow) {
		while (i < r->len && r->path[i] == '/') i++;
		size_t start = i;
		while (i < r->len && r->path[i] != '/') i++;
		size_t end = i;
		if (end == start) break;
		size_t j = end;
		while (j < r->len && r->path[j] == '/') j++;
		int is_leaf = (j >= r->len);
		if (is_leaf && !want_leaf) break;

		char c = r->path[end];
		r->path[end] = 0;
		found |= widen_one(s, ri, end, r->base_fd, r->path, uid,
				   is_leaf);
		r->path[end] = c;
	}
	return found;
}

/* Put every widened mode back, deepest first: restoring a parent that
 * loses its search bit before its child would strand the child. */
static void restore_slot(struct rescue_slot *s)
{
	for (int k = s->n_saved - 1; k >= 0; k--) {
		struct rescue_saved *sv = &s->saved[k];
		struct rescue_route *r  = &s->routes[sv->route];
		if (sv->prefix_len == 0) {
			(void)TAWC_RAW(TAWC_SYS_fchmodat, r->base_fd,
				       (long)".", sv->mode, 0, 0, 0);
			continue;
		}
		char c = r->path[sv->prefix_len];
		r->path[sv->prefix_len] = 0;
		(void)TAWC_RAW(TAWC_SYS_fchmodat, r->base_fd, (long)r->path,
			       sv->mode, 0, 0, 0);
		r->path[sv->prefix_len] = c;
	}
	s->n_saved = 0;
}

void tawcroot_rescue_restore(void)
{
	struct rescue_slot *s = slot_find();
	if (s) restore_slot(s);
}

/* Widen every recorded route. Returns 1 when the caller should retry.
 *
 * Retry whenever the walk saw an app-owned component, even if nothing
 * actually needed widening: a concurrent rescuer may have widened the
 * same directory already, and skipping the re-run would turn that race
 * into a spurious EACCES. A denial we can't own (SELinux, a bind into
 * /dev or /sdcard) finds nothing and keeps the original errno with no
 * second attempt. `overflow` means a route didn't fit, or the widening
 * ran out of save slots partway — the route isn't fully open, so a
 * retry would be noise. The caller restores either way. */
static int rescue_widen(struct rescue_slot *s, int want_leaf)
{
	if (s->overflow || s->n_routes == 0) return 0;
	long uid = tawc_getuid();
	if (uid < 0) return 0;

	int found = 0;
	for (int i = 0; i < s->n_routes; i++)
		found |= widen_route(s, i, (unsigned int)uid, want_leaf);
	return found && !s->overflow;
}

static long rescue_retry(struct rescue_slot *s,
			 const tawcroot_syscall_args *args,
			 tawcroot_handler_fn fn, ucontext_t *uc, long orig)
{
	long rv = orig;
	if (rescue_widen(s, rescue_needs_leaf(args->nr))) {
		s->frozen = 1;
		rv = fn(args, uc);
		s->frozen = 0;
	}
	restore_slot(s);
	return rv;
}

long tawcroot_rescue_open_in_view(const char *guest_path)
{
	/* Already inside a dispatch wrapper (exec_handler's probe opens):
	 * that wrapper owns the slot and does the retry for the whole
	 * handler. Claiming here would reset its recorded routes. */
	if (slot_find()) return tawcroot_open_in_view(guest_path);

	int idx = slot_claim(current_tid());
	if (idx < 0) return tawcroot_open_in_view(guest_path);

	long rv = tawcroot_open_in_view(guest_path);
	if (rv == TAWC_EACCES && tawcroot_identity_euid() == 0) {
		struct rescue_slot *s = &g_slots[idx];
		if (rescue_widen(s, 1)) {
			s->frozen = 1;
			rv = tawcroot_open_in_view(guest_path);
			s->frozen = 0;
		}
		restore_slot(s);
	}
	slot_release(idx);
	return rv;
}

long tawcroot_dispatch_call(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	tawcroot_handler_fn fn = tawcroot_dispatch_get((int)args->nr);
	if (!fn) return TAWC_ENOSYS;

	/* handle_exit forwards exit(2) and never comes back — claiming a
	 * slot for it would leak one per guest thread teardown. */
	if (args->nr == TAWC_SYS_exit) return fn(args, uc);

	int idx = slot_claim(current_tid());
	if (idx < 0) return fn(args, uc);

	long rv = fn(args, uc);
	/* Virtual euid is only consulted here, on the cold path: a guest
	 * that dropped privileges gets the real EACCES (same gate as the
	 * fchmodat/fchownat fakes), and every other trap avoids the
	 * identity seqlock read. */
	if (rv == TAWC_EACCES && tawcroot_identity_euid() == 0)
		rv = rescue_retry(&g_slots[idx], args, fn, uc, rv);
	slot_release(idx);
	return rv;
}
