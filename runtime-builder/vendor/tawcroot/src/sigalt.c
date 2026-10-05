/* See include/sigalt.h.
 *
 * Slot state is one busy byte, CAS-claimed. A slot's recorded guest
 * values are only ever touched by the thread whose altstack it is, so
 * they need no synchronization. Ownership is found from the kernel's
 * view (ss_sp inside the slab), never from a tid: a fork child keeps
 * working with its new tid, and tid reuse can't alias.
 *
 * Known leaks, all bounded by the exhaustion fallback: a thread killed
 * without exit(2); other threads' slots in a fork child; a slot claimed
 * by sigaltstack() inside an SS_AUTODISARM handler (the kernel drops
 * that setting when the handler returns). A CLONE_VM|CLONE_VFORK child
 * that replaces an inherited substituted stack frees the parent's slot
 * under it — accepted; nothing known does this. */

#include <stddef.h>
#include <stdint.h>

#include "errno_neg.h"
#include "sigalt.h"

#ifndef SS_AUTODISARM
# define SS_AUTODISARM (1U << 31)
#endif

_Static_assert(__atomic_always_lock_free(1, 0),
	       "byte atomics must be lock-free for AS-safety");

static unsigned char g_slab[TAWC_SIGALT_SLOTS][TAWC_SIGALT_SLOT]
	__attribute__((aligned(16)));
static uint8_t g_busy[TAWC_SIGALT_SLOTS];
static struct { void *sp; size_t size; } g_guest[TAWC_SIGALT_SLOTS];

static int slot_of(const stack_t *ss)
{
	uintptr_t p = (uintptr_t)ss->ss_sp, base = (uintptr_t)g_slab;
	if (!ss->ss_size || p < base || p >= base + sizeof g_slab)
		return -1;
	return (int)((p - base) / TAWC_SIGALT_SLOT);
}

int tawc_sigalt_is_slab(const void *p)
{
	stack_t ss = { .ss_sp = (void *)(uintptr_t)p, .ss_size = 1 };
	return slot_of(&ss) >= 0;
}

/* kernel on_sig_stack() */
static int on_stack(const stack_t *cur, uintptr_t sp)
{
	if ((unsigned)cur->ss_flags & SS_AUTODISARM) return 0;
	uintptr_t base = (uintptr_t)cur->ss_sp;
	return sp > base && sp - base <= cur->ss_size;
}

long tawc_sigalt_check(const stack_t *cur, uintptr_t guest_sp,
		       const stack_t *new_ss, stack_t *old)
{
	int on = on_stack(cur, guest_sp);
	if (new_ss) {
		if (on) return TAWC_EPERM;
		unsigned mode = (unsigned)new_ss->ss_flags & ~SS_AUTODISARM;
		if (mode != SS_DISABLE && mode != SS_ONSTACK && mode != 0)
			return TAWC_EINVAL;
		if (mode != SS_DISABLE &&
		    new_ss->ss_size < TAWC_SIGALT_KERN_MIN)
			return TAWC_ENOMEM;
	}
	if (old) {
		int k = slot_of(cur);
		old->ss_sp   = k >= 0 ? g_guest[k].sp   : cur->ss_sp;
		old->ss_size = k >= 0 ? g_guest[k].size : cur->ss_size;
		old->ss_flags = (int)
			((cur->ss_size ? (on ? SS_ONSTACK : 0) : SS_DISABLE) |
			 ((unsigned)cur->ss_flags & SS_AUTODISARM));
	}
	return 0;
}

static int slot_claim(void)
{
	for (int k = 0; k < TAWC_SIGALT_SLOTS; k++) {
		uint8_t expected = 0;
		if (__atomic_compare_exchange_n(&g_busy[k], &expected, 1, 0,
						__ATOMIC_ACQ_REL,
						__ATOMIC_RELAXED))
			return k;
	}
	return -1;
}

static void slot_release(int k)
{
	__atomic_store_n(&g_busy[k], 0, __ATOMIC_RELEASE);
}

void tawc_sigalt_commit(stack_t *cur, const stack_t *new_ss)
{
	int k_old = slot_of(cur);
	int k_new = -1;
	unsigned mode = (unsigned)new_ss->ss_flags & ~SS_AUTODISARM;

	*cur = *new_ss;
	if (mode == SS_DISABLE) {
		cur->ss_sp = NULL;
		cur->ss_size = 0;
	} else if (new_ss->ss_size < TAWC_SIGALT_MIN) {
		k_new = k_old >= 0 ? k_old : slot_claim();
		if (k_new >= 0) {
			g_guest[k_new].sp   = new_ss->ss_sp;
			g_guest[k_new].size = new_ss->ss_size;
			cur->ss_sp   = g_slab[k_new];
			cur->ss_size = TAWC_SIGALT_SLOT;
		}
	}
	if (k_old >= 0 && k_old != k_new)
		slot_release(k_old);
}

void tawc_sigalt_thread_exit(const stack_t *cur)
{
	int k = slot_of(cur);
	if (k >= 0) slot_release(k);
}

void tawc_sigalt_reset(void)
{
	for (int k = 0; k < TAWC_SIGALT_SLOTS; k++)
		slot_release(k);
}
