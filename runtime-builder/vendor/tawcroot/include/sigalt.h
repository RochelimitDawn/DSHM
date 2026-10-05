/* Guest sigaltstack virtualization.
 *
 * The SIGSYS handler is registered SA_ONSTACK, so a guest altstack
 * receives our signal frame + handler chain on every trapped syscall
 * the thread makes. A guest stack smaller than TAWC_SIGALT_MIN is
 * swapped for a tawcroot-owned TAWC_SIGALT_SLOT-byte slot from a fixed
 * BSS slab; the guest still reads back its own ss_sp/ss_size.
 *
 * sigaltstack can't be forwarded from the handler: the kernel EPERMs
 * while we run on the altstack, and sigreturn reinstalls uc_stack
 * anyway. So `cur` below is &uc->uc_stack — the kernel applies whatever
 * we leave there when the handler returns (restore_altstack), with the
 * guest's own SP deciding on-stack-ness. That restore ignores errors,
 * so _check replicates do_sigaltstack's validation exactly.
 *
 * Async-signal-safe: lock-free atomics only, no syscalls, no libc.
 * See notes/tawcroot/sigsys-handler.md "Handler stack budget". */

#pragma once

#include <signal.h>
#include <stdint.h>

#if defined(__aarch64__)
# define TAWC_SIGALT_KERN_MIN 5120   /* kernel MINSIGSTKSZ */
# define TAWC_SIGALT_MIN      12288
#elif defined(__x86_64__)
# define TAWC_SIGALT_KERN_MIN 2048
# define TAWC_SIGALT_MIN      8192
#else
# error "unsupported arch"
#endif
#define TAWC_SIGALT_SLOT  16384
#define TAWC_SIGALT_SLOTS 256

/* Pure. Validates `new_ss` (nullable) like the kernel would and fills
 * `old` (nullable) with the guest-visible current state. `guest_sp` is
 * the trapping thread's SP from the ucontext. Returns 0 or -errno;
 * nothing is written on error. */
long tawc_sigalt_check(const stack_t *cur, uintptr_t guest_sp,
		       const stack_t *new_ss, stack_t *old);

/* Apply an already-checked `new_ss` to `cur`, substituting a slab slot
 * for an undersized stack. If the slab is exhausted the guest's stack
 * is installed as-is (no worse than having no floor). */
void tawc_sigalt_commit(stack_t *cur, const stack_t *new_ss);

/* Thread is exiting: free its slot, if it holds one. */
void tawc_sigalt_thread_exit(const stack_t *cur);

/* For tests; not called from production. */
int  tawc_sigalt_is_slab(const void *p);
void tawc_sigalt_reset(void);
