/* See include/io.h. Print helpers that issue raw syscalls via tawc_write.
 *
 * The pure string + memcpy/memset/memmove helpers used to live here too
 * but moved to src/strings.c — they have no syscalls and are linked into
 * the cleat test orchestrator (under hosted glibc) for unit tests.
 */

#include "io.h"
#include "raw_sys.h"

/* Write to stderr (fd 2), not stdout. Trace/error output going to stdout
 * gets captured by shell `$(...)` command substitution and turned into
 * unintended argv (debugging pacman-key reproduces this hilariously:
 * trace lines became `touch`'s argv). Stderr stays out of $() and
 * matches the `tawcroot: …` error-message convention used at init
 * sites in main.c.
 *
 * Numeric helpers (`tawc_io_hex` / `tawc_io_dec`) write to fd 2 too: a
 * single `tawc_io_kv_dec("k", v)` call would otherwise split the label
 * and the value across stdout/stderr, which the testhost smoke parser
 * (cleat, reading one stream) saw as "no steps + garbled values". */
void tawc_io_str(const char *s)
{
	tawc_write(2, s, tawc_strlen(s));
}

void tawc_io_hex(unsigned long v)
{
	char buf[2 + 16];
	const char *digits = "0123456789abcdef";
	buf[0] = '0';
	buf[1] = 'x';
	for (int i = 0; i < 16; i++) {
		buf[17 - i] = digits[v & 0xf];
		v >>= 4;
	}
	tawc_write(2, buf, sizeof buf);
}

void tawc_io_dec(long v)
{
	char buf[24];
	int n = 0;
	int neg = 0;
	unsigned long u;
	/* Negate in unsigned space: -v is UB for LONG_MIN. */
	if (v < 0) { neg = 1; u = -(unsigned long)v; }
	else        { u = (unsigned long)v; }
	if (u == 0) buf[n++] = '0';
	while (u > 0) { buf[n++] = (char)('0' + (u % 10)); u /= 10; }
	if (neg) buf[n++] = '-';
	for (int i = 0, j = n - 1; i < j; i++, j--) {
		char t = buf[i]; buf[i] = buf[j]; buf[j] = t;
	}
	tawc_write(2, buf, (size_t)n);
}

void tawc_io_kv_hex(const char *k, unsigned long v)
{
	tawc_io_str("  ");
	tawc_io_str(k);
	tawc_io_str(" = ");
	tawc_io_hex(v);
	tawc_io_str("\n");
}

void tawc_io_kv_dec(const char *k, long v)
{
	tawc_io_str("  ");
	tawc_io_str(k);
	tawc_io_str(" = ");
	tawc_io_dec(v);
	tawc_io_str("\n");
}

int tawc_io_step(const char *label, int ok)
{
	tawc_io_str(ok ? "  [ok ] " : "  [FAIL] ");
	tawc_io_str(label);
	tawc_io_str("\n");
	return ok ? 0 : 1;
}

void tawc_io_skip(const char *label, const char *reason)
{
	tawc_io_str("  [skip] ");
	tawc_io_str(label);
	tawc_io_str(" (");
	tawc_io_str(reason);
	tawc_io_str(")\n");
}
