/* Build + install the tawcroot seccomp filter. Single source of truth for
 * the trap set; the dispatch table indexes from the same list.
 *
 * install_smoke_filter() installs a minimal filter for the foundation smoke
 * (one TRAP syscall + IP allowlist for the stub). install_filter() takes the
 * full path-bearing trap set generated from the dispatch table.
 */

#pragma once

#include <stddef.h>
#include <stdint.h>

/* Returns 0 on success, -errno on failure (raw syscall semantics). */
long tawcroot_install_smoke_filter(int trap_syscall_nr);

/* Build BPF from a list of syscall numbers to TRAP, plus
 * the IP allowlist. Same shape as the smoke filter; the trap set comes
 * from the caller so the dispatch table and the filter share one source
 * of truth. Returns 0 / -errno. */
long tawcroot_install_filter(const int *trap_nrs, size_t n_traps);

/* Sets PR_SET_NO_NEW_PRIVS, the prerequisite for unprivileged seccomp. */
long tawcroot_set_no_new_privs(void);
