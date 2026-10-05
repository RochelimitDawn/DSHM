/* Handler stack budget.
 *
 * SIGSYS is SA_ONSTACK: the handler runs on the guest thread's
 * sigaltstack when it has one (floored by sigalt.c; this is how Go's
 * 2 KiB goroutine stacks survive), else on the thread's own stack,
 * whose size the guest chose (musl threads default to 128 KiB; the
 * supported floor is the 16 KiB pinned by tests/integration/programs/
 * static_small_stack_open_argv1.S). The kernel signal frame already
 * costs ~4.5 KiB of that, more with xsave/SVE state, so the whole
 * handler call chain must fit in what remains.
 *
 * Enforcement: every production object is compiled with
 * -Wframe-larger-than=1024 -Werror (Makefile TAWC_CFLAGS and build.sh
 * COMMON_CFLAGS). Rules for handler-reachable code:
 *
 *   - Buffers sized by PATH_MAX or by a guest-controlled length come
 *     from path_scratch.h, never the frame.
 *   - Fixed buffers < 256 bytes are fine on the stack.
 *   - Recursive functions (shebang classification) keep per-level
 *     frames minimal — the cap multiplies by the recursion depth.
 *   - Larger staging areas serialized by a lock go in static storage
 *     (see exec_handler.c's exec_lock-guarded buffers).
 *
 * Code that provably never runs on a guest stack — supervisor init and
 * the --exec-child bootstrap before the loader jump, which run on
 * tawcroot's own full-size stack — may opt out of the cap by placing
 * TAWCROOT_FRAME_CAP_EXEMPT at file scope (it applies from that point
 * to the end of the translation unit). Only use it in files/regions
 * where nothing is reachable from the SIGSYS dispatch table.
 *
 * See notes/tawcroot/sigsys-handler.md "Handler stack budget". */

#pragma once

#define TAWCROOT_FRAME_CAP_EXEMPT \
	_Pragma("GCC diagnostic ignored \"-Wframe-larger-than=\"")
