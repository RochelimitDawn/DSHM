/* x86_64 entry trampoline for the manual loader.
 *
 * SysV ABI args:
 *   rdi = sp     (synthesized stack pointer; argc lives at [sp])
 *   rsi = entry  (target instruction address — ld.so or static _start)
 *
 * Sequence:
 *   1. Stash `entry` in rax (nothing is called before the jump, so
 *      a caller-clobbered register is safe).
 *   2. Load new SP. From this point we cannot touch the old stack.
 *   3. Zero rdx (rtld_fini = NULL).
 *   4. Zero rbp (frame-pointer chain ends here).
 *   5. Jump to entry.
 *
 * No prologue / epilogue / callee-save spills — we never return.
 *
 * Notes on what we DON'T need to do:
 *   - FPU / SSE state: glibc's _start re-initializes via _dl_aux_init
 *     and __libc_start_main; the kernel doesn't reset MXCSR on exec
 *     either, so leaving our state is consistent with what the
 *     kernel does on a real exec.
 *   - GP register clearing beyond rdx/rbp: glibc's _start writes
 *     them on first use; leaving them at our values is harmless
 *     and matches what proot does. If a guest reads a GP register
 *     before writing it (very rare, only highly hand-written asm),
 *     we'd diverge from kernel-exec behavior — accept that as a
 *     known wart, document if it ever bites us.
 */

	.text
	.global tawc_loader_jump
	.hidden tawc_loader_jump
	.type   tawc_loader_jump, @function
tawc_loader_jump:
	mov     %rsi, %rax       /* preserve `entry` before clobbering rsp */
	mov     %rdi, %rsp       /* switch to the synthesized stack       */
	xor     %rdx, %rdx       /* rtld_fini = NULL                       */
	xor     %rbp, %rbp       /* frame-pointer chain terminator         */
	jmp     *%rax
	.size tawc_loader_jump, .-tawc_loader_jump

	.section .note.GNU-stack,"",@progbits
