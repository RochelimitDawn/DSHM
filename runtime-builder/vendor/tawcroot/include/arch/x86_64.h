/* x86_64 arch helpers. Kernel-syscall ABI: rax (nr) + rdi rsi rdx r10 r8 r9.
 *
 * `siginfo_t::si_call_addr` and `seccomp_data::instruction_pointer` both point
 * to the POST-syscall PC — the address of the instruction immediately after
 * SYSCALL — because the kernel populates them from `pt_regs->ip`, which the
 * syscall-entry asm has already advanced past the SYSCALL instruction. This
 * is the same value as `mcontext_t::gregs[REG_RIP]` for a SECCOMP_RET_TRAP
 * delivery. The IP allowlist in filter.c therefore matches against the
 * `_ret` label (post-SYSCALL), not the `_insn` label (the SYSCALL itself).
 * Empirically verified — see `src/filter.c` and the foundation smoke driver.
 */

#pragma once

#include <stdint.h>
#include <signal.h>
#include <ucontext.h>
#include <linux/audit.h>

#define TAWCROOT_AUDIT_ARCH AUDIT_ARCH_X86_64

static inline void tawcroot_arch_read_args(const ucontext_t *uc,
					   tawcroot_syscall_args *out)
{
	const greg_t *r = uc->uc_mcontext.gregs;
	out->nr = (long)r[REG_RAX];
	out->a  = (long)r[REG_RDI];
	out->b  = (long)r[REG_RSI];
	out->c  = (long)r[REG_RDX];
	out->d  = (long)r[REG_R10];
	out->e  = (long)r[REG_R8];
	out->f  = (long)r[REG_R9];
}

static inline void tawcroot_arch_write_return(ucontext_t *uc, long rv)
{
	uc->uc_mcontext.gregs[REG_RAX] = (greg_t)rv;
}

static inline uintptr_t tawcroot_arch_sp(const ucontext_t *uc)
{
	return (uintptr_t)uc->uc_mcontext.gregs[REG_RSP];
}

static inline uintptr_t tawcroot_arch_resume_pc(const ucontext_t *uc)
{
	return (uintptr_t)uc->uc_mcontext.gregs[REG_RIP];
}
