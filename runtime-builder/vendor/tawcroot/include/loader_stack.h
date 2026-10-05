/* Initial-stack synthesizer for in-process exec.
 *
 * Layout per System V ABI ELF supplement, top to bottom (high → low
 * addresses; the SP at guest _start points at `argc`):
 *
 *     [strings area]                  high
 *       AT_RANDOM 16 bytes
 *       AT_PLATFORM string
 *       AT_EXECFN string
 *       envp strings (envc total, in original order)
 *       argv strings (argc total, in original order)
 *     [16-byte alignment pad]
 *     [vectors area]
 *       auxv:  pairs of (a_type, a_val); terminated by AT_NULL,0
 *       NULL              (envp terminator)
 *       envp[envc-1]
 *       ...
 *       envp[0]
 *       NULL              (argv terminator)
 *       argv[argc-1]
 *       ...
 *       argv[0]
 *       argc                            <- SP, 16-byte aligned
 *
 * We don't reuse the kernel-built stack — there isn't one for the
 * guest, since we're loading in-process. Everything ld.so / glibc
 * inspects at startup must be present and consistent. A wrong or
 * missing AT_RANDOM is the most common failure (programs `abort()`
 * because __stack_chk_guard initializes to garbage).
 *
 * Reference reading (no code copied):
 *   Linux kernel `fs/binfmt_elf.c::create_elf_tables` — the kernel's
 *     own implementation; canonical for what userspace sees.
 *   glibc `csu/libc-start.c` + `elf/dl-sysdep.c` — what glibc
 *     reads from the stack at startup.
 *   musl ldso/dynlink.c::__dls3 — the auxv consumer for a different
 *     libc. (Musl does not synthesize stacks; it patches kernel-built
 *     ones, so it isn't a structural reference, just a useful sanity
 *     check on what fields a libc expects.)
 */

#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* AT_* constants we emit. Values match Linux uapi (see <elf.h>'s
 * AT_*). We define our own to keep the synth freestanding-clean. */
#define TAWC_AT_NULL          0
#define TAWC_AT_IGNORE        1
#define TAWC_AT_PHDR          3
#define TAWC_AT_PHENT         4
#define TAWC_AT_PHNUM         5
#define TAWC_AT_PAGESZ        6
#define TAWC_AT_BASE          7
#define TAWC_AT_FLAGS         8
#define TAWC_AT_ENTRY         9
#define TAWC_AT_UID          11
#define TAWC_AT_EUID         12
#define TAWC_AT_GID          13
#define TAWC_AT_EGID         14
#define TAWC_AT_PLATFORM     15
#define TAWC_AT_HWCAP        16
#define TAWC_AT_CLKTCK       17
#define TAWC_AT_SECURE       23
#define TAWC_AT_RANDOM       25
#define TAWC_AT_HWCAP2       26
#define TAWC_AT_EXECFN       31
#define TAWC_AT_SYSINFO_EHDR 33

struct tawc_loader_stack_input {
	/* What the guest sees as argv/envp. argv[argc] must be NULL;
	 * envp must be NULL-terminated. */
	int                 argc;
	const char *const *argv;       /* size argc + 1 (NULL term) */
	const char *const *envp;       /* NULL-terminated */

	/* Image placement (from the mapper). */
	uintptr_t  at_phdr;            /* AT_PHDR — in-memory phdr table addr */
	uint16_t   at_phnum;           /* AT_PHNUM */
	uint16_t   at_phent;           /* AT_PHENT */
	uintptr_t  at_base;            /* AT_BASE — ld.so load addr; 0 for static */
	uintptr_t  at_entry;           /* AT_ENTRY — binary's e_entry */

	/* Strings — must be valid for the duration of the call (we copy). */
	const char *at_execfn;         /* AT_EXECFN — guest's program path */
	const char *at_platform;       /* AT_PLATFORM — uname-like ("x86_64", "aarch64") */

	/* AT_RANDOM: caller fills 16 fresh random bytes. Required: glibc
	 * reads these for stack-canary init; missing/zero ⇒ abort. */
	const uint8_t *at_random16;

	/* Inherited verbatim from the host's auxv. Caller pulls these
	 * from /proc/self/auxv at tawcroot init time. Zero values are
	 * filtered: missing entries are simply omitted. */
	uint64_t   at_pagesz;
	uint64_t   at_clktck;
	uint64_t   at_hwcap;
	uint64_t   at_hwcap2;
	uintptr_t  at_sysinfo_ehdr;    /* vDSO base; 0 = omit */

	/* AT_FLAGS — kernel default is 0; we pass through. */
	uint64_t   at_flags;
};

struct tawc_loader_stack_out {
	uintptr_t  sp;                 /* guest SP at _start; 16-byte aligned */
	uintptr_t  data_lo;            /* low end of all written data */
	uintptr_t  data_hi;            /* high end of all written data */
};

/* Write a SysV-ABI initial stack into the buffer
 * `[region_low, region_low + region_size)` (low → high addresses).
 * The stack data lives at the *high* end of the region; SP points at
 * argc near the bottom of the data. The region is left untouched
 * below `out->data_lo` — caller can use that for guard pages.
 *
 * Returns 0 on success, -ENOMEM if the region is too small, -EINVAL
 * for malformed input (NULL argv, missing at_random16, etc).
 *
 * Endianness: assumes host endianness == guest endianness (we only
 * load native-arch guests).
 */
long tawc_loader_build_stack(void *region_low, size_t region_size,
                             const struct tawc_loader_stack_input *in,
                             struct tawc_loader_stack_out *out);

/* Walk a synthesized stack at `sp` and pull out argc/argv/envp/auxv
 * pointers, the way glibc's _start does. Used by the unit tests as
 * an oracle: we build a stack, walk it back, and compare against the
 * input. (The production guest's _start is glibc/musl/bionic startup
 * code — we don't reimplement it; we just verify our synth produces
 * something a real ld.so can parse.)
 *
 *  out_argc:  written
 *  out_argv:  pointer to argv pointer array on the stack (NULL-term)
 *  out_envp:  pointer to envp pointer array on the stack (NULL-term)
 *  out_auxv:  pointer to first auxv entry (terminated by AT_NULL pair)
 */
struct tawc_loader_aux_entry {
	uint64_t a_type;
	uint64_t a_val;
};

void tawc_loader_walk_stack(uintptr_t sp,
                            int *out_argc,
                            char *const **out_argv,
                            char *const **out_envp,
                            const struct tawc_loader_aux_entry **out_auxv);

#ifdef __cplusplus
}
#endif
