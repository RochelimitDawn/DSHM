/* See include/proctitle.h. Runs at load time (never from the SIGSYS
 * handler), so statics are safe: the process is single-threaded until
 * the guest starts. */

#include <stddef.h>

#include <sys/prctl.h>

#include "io.h"
#include "proctitle.h"
#include "raw_sys.h"
#include "tawc_string.h"

/* Kernel arg region bounds, from the initial stack. The kernel copied
 * the execveat argv strings back-to-back; /proc/<pid>/cmdline is
 * exactly these bytes. */
static char *g_arg_start;
static char *g_arg_end;

/* Bounce buffer: sized like syscalls_exec.c's collection caps (16 KB
 * path + 64 KB argv) plus shebang expansion slack. BSS, not stack. */
#define TITLE_BOUNCE_SIZE ((16 + 64 + 4) * 1024)
static char g_bounce[TITLE_BOUNCE_SIZE];

void tawcroot_proctitle_stash(int argc, char **argv)
{
	if (argc <= 0 || !argv || !argv[0] || !argv[argc - 1]) return;
	g_arg_start = argv[0];
	g_arg_end   = argv[argc - 1] + tawc_strlen(argv[argc - 1]) + 1;
}

void tawcroot_proctitle_apply(const char *exec_path,
                              int argc, const char *const *argv)
{
	/* comm first — it reads exec_path, which may live inside the
	 * region the cmdline rewrite below overwrites (prod entry). */
	if (exec_path) {
		const char *base = exec_path;
		for (const char *p = exec_path; *p; p++)
			if (*p == '/') base = p + 1;
		char comm[16];  /* TASK_COMM_LEN */
		size_t n = 0;
		while (base[n] && n < sizeof comm - 1) {
			comm[n] = base[n];
			n++;
		}
		comm[n] = 0;
		(void)tawc_prctl(PR_SET_NAME, (long)comm, 0, 0, 0);
	}

	if (!g_arg_start || g_arg_end <= g_arg_start || !argv) return;

	/* NUL-join argv into the bounce (sources may overlap the region),
	 * then copy over the region and NUL-fill the tail. Truncate to
	 * whichever of bounce/region fills first; the exec handler sizes
	 * the region to the exact post-shebang argv byte count, so real
	 * truncation only happens when the file changed between the
	 * handler's probe and our load (TOCTOU). */
	size_t cap = (size_t)(g_arg_end - g_arg_start);
	if (cap > sizeof g_bounce) cap = sizeof g_bounce;
	size_t len = 0;
	for (int i = 0; i < argc && argv[i]; i++) {
		const char *s = argv[i];
		while (*s && len < cap - 1) g_bounce[len++] = *s++;
		if (len < cap) g_bounce[len++] = 0;
		if (len >= cap - 1) break;
	}
	for (size_t k = 0; k < len; k++) g_arg_start[k] = g_bounce[k];
	for (char *p = g_arg_start + len; p < g_arg_end; p++) *p = 0;
	/* Last byte NUL even on truncation: a non-NUL byte at arg_end-1
	 * makes the kernel's cmdline read continue into the env region. */
	g_arg_end[-1] = 0;
}
