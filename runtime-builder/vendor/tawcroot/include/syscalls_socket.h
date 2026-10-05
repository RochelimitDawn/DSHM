/* AF_UNIX socket address translation for bind/connect/accept. See
 * syscalls_socket.c.
 */

#pragma once

/* Register the socket handler set in the dispatch table. Called from
 * tawcroot_dispatch_init. */
void tawcroot_socket_register(void);

/* Forget the recorded tier-3 parent-dir fds (see sock_parents in
 * syscalls_socket.c). Test teardown only — the fds themselves live in
 * tawcroot_reserved_fds and are closed by the caller. */
void tawcroot_socket_reset(void);
