/* Stateful virtual identity. See include/identity.h.
 *
 * One process-wide tawc_identity struct guarded by a seqlock, same
 * pattern as signal_shadow.c's sigaction shadow (even = stable, odd =
 * writer in progress; per-word relaxed atomics under the seq pair keep
 * the copies well-defined under the C memory model). POSIX identity is
 * process-wide — glibc broadcasts set*id to all threads — so a single
 * struct is the correct semantic, not an approximation.
 *
 * Writers are the trapped set*id syscalls plus the --exec-child
 * restore and the register-time reset. Readers are the get*id handlers
 * and the fake-decoration paths (chmod/chown gating). Multi-writer
 * safety comes from the CAS-claim spin on the sequence word.
 *
 * Known bounded failures (same stance as signal_shadow's action lock):
 * a set*id issued from a guest signal handler that interrupted another
 * set*id mid-write on the SAME thread would self-deadlock on the
 * writer claim; likewise an identity READER from such a handler —
 * get*id, or any syscall whose handler consults
 * tawcroot_identity_euid() (the chmod/chown privilege gates) — spins
 * on the odd seq forever; a fork while another thread holds the
 * writer lock leaves the child's seq odd forever. All need a guest
 * signal/fork landing inside a set*id's few-dozen-instruction write
 * window — no known workload does any of these. A vfork child calling
 * set*id before exec would corrupt the parent's view (address space
 * is shared) — same bounded-failure stance as the chroot globals.
 *
 * Syscall semantics follow the kernel's (kernel/sys.c), applied to the
 * virtual state, with privilege ⇔ euid == 0 standing in for
 * CAP_SETUID/CAP_SETGID ("fake root has all caps, others none" is the
 * whole capability model — capget/capset are not modeled further).
 * On every e[ug]id change, fs[ug]id follows, as the kernel does.
 */

#include <stddef.h>
#include <stdint.h>

#include "dispatch.h"
#include "errno_neg.h"
#include "identity.h"
#include "sysnr.h"
#include "usercopy.h"

/* Same AS-safety requirement as signal_shadow.c: these must be inline
 * lock-free ops, never a libgcc outline call that may take a mutex. */
_Static_assert(__atomic_always_lock_free(sizeof(uint32_t), 0),
	       "uint32_t atomics must be lock-free for AS-safety");

_Static_assert(sizeof(tawc_identity) % sizeof(uint32_t) == 0,
	       "identity must be word-copyable");
#define IDENT_WORDS (sizeof(tawc_identity) / sizeof(uint32_t))

static uint32_t      g_ident_seq;  /* even=stable, odd=writing */
static tawc_identity g_ident;      /* protected by g_ident_seq */

/* (uid_t)-1: "keep" in the setre*id/setres*id families, invalid in
 * set[ug]id. */
#define ID_KEEP UINT32_MAX

void tawcroot_identity_get(tawc_identity *out)
{
	uint32_t       *dst = (uint32_t *)out;
	const uint32_t *src = (const uint32_t *)&g_ident;
	for (;;) {
		uint32_t s1 = __atomic_load_n(&g_ident_seq, __ATOMIC_ACQUIRE);
		if (s1 & 1) continue;  /* writer in progress, retry */
		for (size_t i = 0; i < IDENT_WORDS; i++)
			dst[i] = __atomic_load_n(&src[i], __ATOMIC_RELAXED);
		__atomic_thread_fence(__ATOMIC_ACQUIRE);
		uint32_t s2 = __atomic_load_n(&g_ident_seq, __ATOMIC_RELAXED);
		if (s1 == s2) return;
		/* writer landed mid-copy, retry */
	}
}

/* Claim the writer lock (seq goes odd). While held, g_ident is stable:
 * only writers mutate it and they serialize here, so the caller may
 * read g_ident with plain loads to compute its update. */
static uint32_t ident_writer_acquire(void)
{
	for (;;) {
		uint32_t s = __atomic_load_n(&g_ident_seq, __ATOMIC_RELAXED);
		if (s & 1) continue;  /* another writer holds the lock */
		uint32_t expected = s;
		if (__atomic_compare_exchange_n(&g_ident_seq, &expected,
						s + 1, 0,
						__ATOMIC_ACQUIRE,
						__ATOMIC_RELAXED))
			return s;
	}
}

/* Publish `next` and release (seq goes even). Also used on the error
 * path with an unmodified copy — a spurious seq bump only costs
 * concurrent readers one retry. */
static void ident_writer_release(uint32_t s, const tawc_identity *next)
{
	/* Order the claim's odd-seq store before the data stores below.
	 * Without this write barrier the data stores can become visible
	 * before the odd seq, and a reader can copy fresh words while
	 * both its seq reads still return the stale even value —
	 * accepting a torn snapshot. Same reason Linux's
	 * write_seqcount_begin needs smp_wmb(); pairs with the reader's
	 * acquire fence. */
	__atomic_thread_fence(__ATOMIC_RELEASE);
	const uint32_t *src = (const uint32_t *)next;
	uint32_t       *dst = (uint32_t *)&g_ident;
	for (size_t i = 0; i < IDENT_WORDS; i++)
		__atomic_store_n(&dst[i], src[i], __ATOMIC_RELAXED);
	__atomic_store_n(&g_ident_seq, s + 2, __ATOMIC_RELEASE);
}

void tawcroot_identity_load(const tawc_identity *in)
{
	tawc_identity id = *in;
	if (id.ngroups > TAWC_IDENTITY_NGROUPS)
		id.ngroups = TAWC_IDENTITY_NGROUPS;
	uint32_t s = ident_writer_acquire();
	ident_writer_release(s, &id);
}

void tawcroot_identity_reset(void)
{
	tawc_identity id = { 0 };
	id.ngroups   = 1;
	id.groups[0] = 0;
	uint32_t s = ident_writer_acquire();
	ident_writer_release(s, &id);
}

uint32_t tawcroot_identity_euid(void)
{
	tawc_identity id;
	tawcroot_identity_get(&id);
	return id.euid;
}

/* ----- set*id rule engine ----------------------------------------- */

/* The uid and gid families follow identical rules over their own
 * (r, e, s, fs) quad; only the privilege predicate is shared (euid ==
 * 0 — the kernel checks CAP_SETGID for the gid family, which fake
 * root's euid stands in for). A view of pointers into a local copy
 * lets one rule implementation serve both. */
struct id_view {
	uint32_t *r, *e, *s, *fs;
};

static struct id_view uid_view(tawc_identity *id)
{
	return (struct id_view){ &id->ruid, &id->euid, &id->suid,
				 &id->fsuid };
}

static struct id_view gid_view(tawc_identity *id)
{
	return (struct id_view){ &id->rgid, &id->egid, &id->sgid,
				 &id->fsgid };
}

/* setuid/setgid: privileged → r=e=s=v; unprivileged → allowed iff
 * v ∈ {r, s}, sets e only. fs follows e. (uid_t)-1 is EINVAL. */
static long do_setid(uint32_t v, int is_gid)
{
	if (v == ID_KEEP) return TAWC_EINVAL;
	uint32_t seq = ident_writer_acquire();
	tawc_identity id = g_ident;
	struct id_view w = is_gid ? gid_view(&id) : uid_view(&id);
	long rv = 0;
	if (id.euid == 0) {
		*w.r = *w.e = *w.s = v;
	} else if (v == *w.r || v == *w.s) {
		*w.e = v;
	} else {
		rv = TAWC_EPERM;
	}
	if (rv == 0) *w.fs = *w.e;
	ident_writer_release(seq, &id);
	return rv;
}

/* setreuid/setregid: unprivileged → r ∈ {old r, old e},
 * e ∈ {old r, old e, old s}. Saved-id update rule: if r was set, or e
 * was set to something other than the old r, then s = new e. fs
 * follows e. */
static long do_setreid(uint32_t r, uint32_t e, int is_gid)
{
	uint32_t seq = ident_writer_acquire();
	tawc_identity id = g_ident;
	struct id_view w = is_gid ? gid_view(&id) : uid_view(&id);
	int priv = id.euid == 0;
	uint32_t old_r = *w.r, old_e = *w.e, old_s = *w.s;
	long rv = 0;
	/* Validate BOTH ids before applying either: set*id calls are
	 * atomic — the kernel commits creds only after every check
	 * passes, so EPERM must leave the state untouched. */
	if (r != ID_KEEP && !(priv || r == old_r || r == old_e))
		rv = TAWC_EPERM;
	if (e != ID_KEEP &&
	    !(priv || e == old_r || e == old_e || e == old_s))
		rv = TAWC_EPERM;
	if (rv == 0) {
		if (r != ID_KEEP) *w.r = r;
		if (e != ID_KEEP) *w.e = e;
		if (r != ID_KEEP || (e != ID_KEEP && e != old_r))
			*w.s = *w.e;
		*w.fs = *w.e;
	}
	ident_writer_release(seq, &id);
	return rv;
}

/* setresuid/setresgid: unprivileged → each non-keep value must be one
 * of the old {r, e, s}. fs follows e. */
static long do_setresid(uint32_t r, uint32_t e, uint32_t s, int is_gid)
{
	uint32_t seq = ident_writer_acquire();
	tawc_identity id = g_ident;
	struct id_view w = is_gid ? gid_view(&id) : uid_view(&id);
	int priv = id.euid == 0;
	uint32_t old_r = *w.r, old_e = *w.e, old_s = *w.s;
	long rv = 0;
	const uint32_t v[3] = { r, e, s };
	for (int i = 0; i < 3; i++) {
		if (v[i] == ID_KEEP) continue;
		if (!priv && v[i] != old_r && v[i] != old_e && v[i] != old_s) {
			rv = TAWC_EPERM;
			break;
		}
	}
	if (rv == 0) {
		if (r != ID_KEEP) *w.r = r;
		if (e != ID_KEEP) *w.e = e;
		if (s != ID_KEEP) *w.s = s;
		*w.fs = *w.e;
	}
	ident_writer_release(seq, &id);
	return rv;
}

/* setfsuid/setfsgid: returns the PREVIOUS fs id, never an error.
 * Applies iff privileged or v ∈ {r, e, s, fs}. (uid_t)-1 is an
 * always-invalid uid, so it can never match and never applies. */
static long do_setfsid(uint32_t v, int is_gid)
{
	uint32_t seq = ident_writer_acquire();
	tawc_identity id = g_ident;
	struct id_view w = is_gid ? gid_view(&id) : uid_view(&id);
	uint32_t prev = *w.fs;
	if (v != ID_KEEP &&
	    (id.euid == 0 ||
	     v == *w.r || v == *w.e || v == *w.s || v == *w.fs))
		*w.fs = v;
	ident_writer_release(seq, &id);
	return (long)prev;
}

/* ----- dispatch handlers ------------------------------------------- */

static long handle_setuid(const tawcroot_syscall_args *args, ucontext_t *uc)
{ (void)uc; return do_setid((uint32_t)args->a, 0); }

static long handle_setgid(const tawcroot_syscall_args *args, ucontext_t *uc)
{ (void)uc; return do_setid((uint32_t)args->a, 1); }

static long handle_setreuid(const tawcroot_syscall_args *args, ucontext_t *uc)
{ (void)uc; return do_setreid((uint32_t)args->a, (uint32_t)args->b, 0); }

static long handle_setregid(const tawcroot_syscall_args *args, ucontext_t *uc)
{ (void)uc; return do_setreid((uint32_t)args->a, (uint32_t)args->b, 1); }

static long handle_setresuid(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	return do_setresid((uint32_t)args->a, (uint32_t)args->b,
			   (uint32_t)args->c, 0);
}

static long handle_setresgid(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	return do_setresid((uint32_t)args->a, (uint32_t)args->b,
			   (uint32_t)args->c, 1);
}

static long handle_setfsuid(const tawcroot_syscall_args *args, ucontext_t *uc)
{ (void)uc; return do_setfsid((uint32_t)args->a, 0); }

static long handle_setfsgid(const tawcroot_syscall_args *args, ucontext_t *uc)
{ (void)uc; return do_setfsid((uint32_t)args->a, 1); }

/* setgroups: privileged only. Errno order mirrors the kernel: size
 * sanity (EINVAL) → capability (EPERM) → allocation (our fixed shadow
 * overflowing is ENOMEM — kernel-plausible; sshd/su set a handful) →
 * copy (EFAULT). The 65536 bound is the kernel's NGROUPS_MAX, and the
 * size arg is `int` in the kernel ABI, so truncate like it does. */
static long handle_setgroups(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	int n = (int)args->a;
	const void *list = (const void *)(uintptr_t)args->b;
	if (n < 0 || n > 65536) return TAWC_EINVAL;
	/* Pre-check privilege before touching the guest list so an
	 * unprivileged caller with a bad pointer gets EPERM, not EFAULT
	 * (kernel order). The authoritative check re-runs under the
	 * writer lock. */
	if (tawcroot_identity_euid() != 0) return TAWC_EPERM;
	if (n > (int)TAWC_IDENTITY_NGROUPS) return TAWC_ENOMEM;
	uint32_t local[TAWC_IDENTITY_NGROUPS];
	uint32_t count = 0;
	if (n > 0) {
		if (!list) return TAWC_EFAULT;
		long ce = tawc_copy_from_guest(local, (size_t)n * 4, list);
		if (ce < 0) return ce;
		count = (uint32_t)n;
	}
	uint32_t seq = ident_writer_acquire();
	tawc_identity id = g_ident;
	long rv = 0;
	if (id.euid != 0) {
		rv = TAWC_EPERM;
	} else {
		id.ngroups = count;
		for (uint32_t i = 0; i < count; i++) id.groups[i] = local[i];
	}
	ident_writer_release(seq, &id);
	return rv;
}

/* getgroups: answer from the shadow. Without this trap the kernel
 * leaks the app's Android gids (3003/9997/…) into `id` output. The
 * size arg is `int` in the kernel ABI — truncate like it does. */
static long handle_getgroups(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	int n = (int)args->a;
	void *list = (void *)(uintptr_t)args->b;
	if (n < 0) return TAWC_EINVAL;
	tawc_identity id;
	tawcroot_identity_get(&id);
	if (n == 0) return (long)id.ngroups;
	if ((uint32_t)n < id.ngroups) return TAWC_EINVAL;
	if (id.ngroups > 0) {
		if (!list) return TAWC_EFAULT;
		long ce = tawc_copy_to_guest(list, id.groups,
					     (size_t)id.ngroups * 4);
		if (ce < 0) return ce;
	}
	return (long)id.ngroups;
}

static long handle_getuid(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)args; (void)uc;
	tawc_identity id;
	tawcroot_identity_get(&id);
	return (long)id.ruid;
}

static long handle_geteuid(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)args; (void)uc;
	tawc_identity id;
	tawcroot_identity_get(&id);
	return (long)id.euid;
}

static long handle_getgid(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)args; (void)uc;
	tawc_identity id;
	tawcroot_identity_get(&id);
	return (long)id.rgid;
}

static long handle_getegid(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)args; (void)uc;
	tawc_identity id;
	tawcroot_identity_get(&id);
	return (long)id.egid;
}

/* getresuid/getresgid take three out-pointer args. Write the tracked
 * triple through the EFAULT-safe copy helper. */
static long do_getresid(const tawcroot_syscall_args *args, int is_gid)
{
	tawc_identity id;
	tawcroot_identity_get(&id);
	struct id_view w = is_gid ? gid_view(&id) : uid_view(&id);
	const uint32_t vals[3] = { *w.r, *w.e, *w.s };
	void *p[3] = {
		(void *)(uintptr_t)args->a,
		(void *)(uintptr_t)args->b,
		(void *)(uintptr_t)args->c,
	};
	for (int i = 0; i < 3; i++) {
		if (!p[i]) return TAWC_EFAULT;  /* kernel rejects NULL too */
		long r = tawc_copy_to_guest(p[i], &vals[i], sizeof vals[i]);
		if (r < 0) return r;
	}
	return 0;
}

static long handle_getresuid(const tawcroot_syscall_args *args, ucontext_t *uc)
{ (void)uc; return do_getresid(args, 0); }

static long handle_getresgid(const tawcroot_syscall_args *args, ucontext_t *uc)
{ (void)uc; return do_getresid(args, 1); }

void tawcroot_identity_register(void)
{
	tawcroot_identity_reset();

	tawcroot_dispatch_install(TAWC_SYS_getuid,     handle_getuid);
	tawcroot_dispatch_install(TAWC_SYS_geteuid,    handle_geteuid);
	tawcroot_dispatch_install(TAWC_SYS_getgid,     handle_getgid);
	tawcroot_dispatch_install(TAWC_SYS_getegid,    handle_getegid);
	tawcroot_dispatch_install(TAWC_SYS_getresuid,  handle_getresuid);
	tawcroot_dispatch_install(TAWC_SYS_getresgid,  handle_getresgid);
	tawcroot_dispatch_install(TAWC_SYS_getgroups,  handle_getgroups);

	tawcroot_dispatch_install(TAWC_SYS_setuid,     handle_setuid);
	tawcroot_dispatch_install(TAWC_SYS_setgid,     handle_setgid);
	tawcroot_dispatch_install(TAWC_SYS_setreuid,   handle_setreuid);
	tawcroot_dispatch_install(TAWC_SYS_setregid,   handle_setregid);
	tawcroot_dispatch_install(TAWC_SYS_setresuid,  handle_setresuid);
	tawcroot_dispatch_install(TAWC_SYS_setresgid,  handle_setresgid);
	tawcroot_dispatch_install(TAWC_SYS_setfsuid,   handle_setfsuid);
	tawcroot_dispatch_install(TAWC_SYS_setfsgid,   handle_setfsgid);
	tawcroot_dispatch_install(TAWC_SYS_setgroups,  handle_setgroups);
}
