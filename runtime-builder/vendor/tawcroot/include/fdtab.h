/* Internal-fd reservation.
 *
 * tawcroot keeps a handful of long-lived fds open across guest
 * execution: the rootfs O_PATH fd, one O_PATH fd per `-b src:dst`
 * bind, the linkstore dirfds, tier-3 socket parent dirs, and the shm
 * segments. If the guest can `close()` those, our handler's `*at` calls
 * start failing, *or worse* — the kernel could later assign that slot
 * to a guest-opened file, after which our path translator would route
 * guest opens through whatever inode the guest chose.
 *
 * Mitigation: dup every internal fd to a high number (at or above
 * `TAWCROOT_RESERVED_FD_BASE`), record it in `tawcroot_reserved_fds[]`,
 * and trap the fd-shape syscalls (`close`, `close_range`, `dup`,
 * `dup2`, `dup3`, `fcntl`, `fchdir`, `getdents*`). The trapping
 * handlers make every recorded fd return `-EBADF` from the guest's
 * perspective, exactly as if the fd were never opened.
 *
 * The base is a *placement* floor (and a BPF fast-path floor), not a
 * range we own. The guest may legitimately hold fds above it: nothing
 * traps `setrlimit`, Android's soft `RLIMIT_NOFILE` is already 32768+,
 * and the kernel hands out fds past 1000 as soon as a guest opens that
 * many. Every handler decision here is therefore an exact membership
 * test against the table. Treating the whole half-space `[BASE, ∞)` as
 * ours — the shape this file used to describe — meant `dup2`/`F_DUPFD`
 * failed on the guest's own descriptors and `close_range` silently
 * skipped them (an fd leak across exec for exactly the programs careful
 * enough to closefrom).
 *
 * What keeps our fds and the guest's disjoint is the kernel: it never
 * hands out an fd number that is already open, so a reservation
 * (`fcntl(F_DUPFD*, BASE)`) can never land on a live guest fd, and no
 * guest open can land on one of ours. The only route by which a guest
 * can name a specific number is `dup2`/`dup3` with an explicit `newfd`
 * (rejected with `-EBADF` when it names a reserved fd) — plus
 * `pidfd_getfd`, which we don't implement. The corollary is that a
 * number we stop using must leave the table *before* the kernel can
 * recycle it: see `tawcroot_fd_forget_reserved`.
 */

#pragma once

#include <stddef.h>

#define TAWCROOT_RESERVED_FD_BASE 1000

/* Cap on simultaneously reserved fds: rootfs (1) + binds (TAWCROOT_MAX_BINDS).
 * Sized generously above the 8 we ship today. Only matters as the BPF
 * filter generator's array bound; runtime growth is the bind table's. */
#define TAWCROOT_MAX_RESERVED_FDS 64

/* Tombstone: a slot whose fd has been given up (tawcroot_fd_forget_
 * reserved). Any value below TAWCROOT_RESERVED_FD_BASE means "not a
 * live reservation", which makes the zero-initialized table free for
 * the taking without an init pass. It must not be a plausible fd
 * number, or the getdents64 filter would hide a real guest fd. */
#define TAWCROOT_RESERVED_FD_NONE (-1)

/* The set of fds tawcroot has reserved (rootfs + bind sources, linkstore,
 * socket parents, shm segments). Slots in [0, tawcroot_n_reserved_fds)
 * hold either a live fd (>= TAWCROOT_RESERVED_FD_BASE) or
 * TAWCROOT_RESERVED_FD_NONE; the count is a high-water mark that only
 * grows, and freed slots are reused in place. The SIGSYS handler reads
 * both without locking. Defined in syscalls_fd.c. */
extern int    tawcroot_reserved_fds[TAWCROOT_MAX_RESERVED_FDS];
extern size_t tawcroot_n_reserved_fds;

/* True iff `fd` is one of the specific reserved slots.
 *
 * Performance: pacman/gpgme's fork-and-close-all-fds dance hammers
 * `close(fd)` for fd in [3, RLIMIT_NOFILE) — ~1M iterations on Android.
 * The BPF filter's close fast-path is a RANGE compare: it only TRAPs
 * close(fd >= TAWCROOT_RESERVED_FD_BASE), so the ~1M low-fd closes skip
 * the handler entirely. That range is a conservative superset of the
 * table (guest fds above the base trap too, and the handler forwards
 * their close to the kernel); the point of the range rather than a
 * per-fd JEQ list is that fds reserved AFTER filter install (shm_open,
 * post-chroot root fd) are covered, which the baked-in list wasn't. */
static inline int tawcroot_fd_is_reserved(int fd)
{
	if (fd < TAWCROOT_RESERVED_FD_BASE) return 0;
	/* Acquire pairs with the release publication in
	 * tawcroot_fd_record_reserved: seeing the count means seeing the
	 * slot writes below it. */
	size_t n = __atomic_load_n(&tawcroot_n_reserved_fds,
				   __ATOMIC_ACQUIRE);
	for (size_t i = 0; i < n; i++) {
		if (__atomic_load_n(&tawcroot_reserved_fds[i],
				    __ATOMIC_RELAXED) == fd) return 1;
	}
	return 0;
}

/* Move `fd` to the next free slot at or above TAWCROOT_RESERVED_FD_BASE
 * via `fcntl(F_DUPFD_CLOEXEC, base)` and close the original. Returns
 * the new fd on success, -errno on failure. -ENOSPC when the table is
 * full — failing closed, because an unrecorded high fd would be
 * invisible to tawcroot_fd_is_reserved (i.e. not actually protected);
 * the original fd is left open in that case. Call BEFORE the seccomp
 * filter goes up (close/fcntl would otherwise trap into a not-yet-
 * registered handler); post-init callers (chroot, shm, lazy linkstore
 * fds) go through the raw stub, which the filter allowlists. */
long tawcroot_fd_reserve(int fd);

/* Record `fd` — which must already be at or above the base — in the
 * reserved table. Returns 0, or -ENOSPC when every slot is live.
 * The single publication path into the table: callers that place their
 * own fd (shm, which needs a non-CLOEXEC dup) come through here rather
 * than open-coding the store, so there is one memory-ordering
 * discipline instead of two. */
long tawcroot_fd_record_reserved(int fd);

/* Drop `fd` from the table, freeing its slot for reuse. Call BEFORE
 * closing the fd: from the moment it is closed the kernel may hand that
 * number to the guest, and a stale entry would make us lie about a
 * descriptor the guest owns (faked close, -EBADF from dup2 onto it,
 * hidden from /proc/self/fd). Unknown fds are ignored. */
void tawcroot_fd_forget_reserved(int fd);

/* Register the close/dup/fcntl handler set in the dispatch table.
 * Called from tawcroot_dispatch_init. */
void tawcroot_fd_register(void);
