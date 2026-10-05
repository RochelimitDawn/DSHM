/* aarch64 entry trampoline for the manual loader.
 *
 * AArch64 PCS args:
 *   x0 = sp     (synthesized stack pointer; argc at [sp])
 *   x1 = entry  (target instruction address)
 *
 * Sequence mirrors x86_64:
 *   1. Save `entry` (x1 → x9, caller-saved scratch).
 *   2. Load new SP from x0. Hardware enforces SP-alignment to 16 on
 *      use; the stack synth guarantees this.
 *   3. Zero x0 (rtld_fini argument — SysV-arm64 passes it in x0
 *      at _start time, NOT rdx like x86_64).
 *   4. Zero x29 (frame pointer chain terminator).
 *   5. Branch to entry via x9.
 *
 * Like the x86_64 version: no prologue/epilogue, no return.
 */

	.text
	.global tawc_loader_jump
	.hidden tawc_loader_jump
	.type   tawc_loader_jump, %function
tawc_loader_jump:
	mov     x9, x1            /* preserve entry                       */
	mov     sp, x0            /* switch SP                            */
	mov     x0, #0            /* rtld_fini = NULL                     */
	mov     x29, #0           /* frame-pointer terminator             */
	br      x9
	.size tawc_loader_jump, .-tawc_loader_jump

	.section .note.GNU-stack,"",%progbits
