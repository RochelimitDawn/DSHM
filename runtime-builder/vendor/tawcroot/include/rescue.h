/* Lazy CAP_DAC_OVERRIDE emulation for the fake root.
 *
 * tawcroot fakes uid 0, but the kernel sees the app uid on every real
 * syscall and `untrusted_app` never holds CAP_DAC_OVERRIDE. A mode
 * that denies the *owner* therefore blocks the guest's "root" too:
 * `chmod 555 / && mkdir /x` fails EACCES where real root succeeds.
 * proot's fake_id0 solved this by chmod-ing every path component to
 * u+rw(x) before *every* path syscall and restoring at syscall exit.
 * We do the same thing, but only on the error path — the happy path
 * must not pay for it.
 *
 * Shape: one wrapper at the dispatch call site
 * (`tawcroot_dispatch_call`, used by the SIGSYS handler and by the
 * hosted tests). The path translator drops each translated route
 * (base fd + suffix) into a per-thread rescue slot the wrapper owns.
 * When the handler returns -EACCES and the virtual euid is 0, the
 * wrapper widens the app-owned inodes along those routes, re-runs the
 * handler once, and restores the old modes in reverse order whatever
 * the result. New path handlers are covered without touching this
 * file; only the leaf-access table below is per-syscall.
 *
 * See notes/tawcroot/path-translation.md §"DAC override".
 */

#pragma once

#include <ucontext.h>

#include "arch.h"

/* Dispatch one trapped syscall through the rescue wrapper. Looks the
 * handler up in the dispatch table, runs it, and retries it once under
 * widened modes if it came back -EACCES at virtual euid 0. Returns
 * -ENOSYS when no handler is registered (same contract the bare
 * dispatch lookup had). */
long tawcroot_dispatch_call(const tawcroot_syscall_args *args,
			    ucontext_t *uc);

/* Record a translated route for the in-flight syscall. Called by the
 * path translator; a no-op when the calling thread holds no rescue
 * slot (supervisor init, --exec-child bootstrap). `path` is the
 * suffix relative to `base_fd`; "" means base_fd itself. Over-long
 * paths and more routes than the fixed cap mark the call unrescuable
 * rather than truncating. */
void tawcroot_rescue_note(int base_fd, const char *path);

/* tawcroot_open_in_view() with the same lazy DAC override around it,
 * for callers outside the dispatch wrapper — the --exec-child loader,
 * which re-opens the guest binary (and its PT_INTERP / shebang
 * interpreters) by path in a fresh process. Without it a `--x--x--x`
 * binary that handle_execve just rescued would fail again on the far
 * side of the re-exec. Passes straight through when the caller already
 * holds a slot. */
long tawcroot_rescue_open_in_view(const char *guest_path);

/* Restore any modes widened for the in-flight syscall, now. Handlers
 * that hand control away without returning to the wrapper must call
 * this first — today that is execve/execveat, whose commit step
 * execveat()s and never comes back. Idempotent; a no-op when nothing
 * was widened. */
void tawcroot_rescue_restore(void);
