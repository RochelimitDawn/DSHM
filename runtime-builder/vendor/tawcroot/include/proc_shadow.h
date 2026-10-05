/* /proc shadow synthesis + /proc/self path classification.
 *
 * A handful of /proc files need a synthesized stand-in before the guest
 * may open them — either because the kernel's content leaks host paths
 * the guest's world view doesn't contain (/proc/self/maps), or because
 * Android's sandbox makes the real file unreadable in a way guests
 * mishandle (/proc/sys/kernel/overflow{uid,gid}, /proc/bus/pci/devices,
 * /proc/stat, /proc/version, /proc/uptime, /proc/loadavg). See the
 * comments on each synthesizer for the per-file story. Shadows cover
 * metadata too — stat/statx/access are synthesized from the same
 * classifier, not passed to the (denied) real inode.
 *
 * Everything here is async-signal-safe (raw syscalls, no allocation
 * outside anonymous mmaps that are unmapped before return).
 */

#pragma once

#include <stddef.h>

struct stat;
struct statx;

/* Which shadowed /proc file `path` names, if any.
 *
 * ONE classifier feeds all four surfaces below (open, stat, statx,
 * access). Metadata calls used to translate the path and hand it to the
 * kernel, so `cat /proc/stat` read a synthesized file while
 * `stat /proc/stat` hit the real, SELinux-denied inode. A path that
 * stats fine but fails to open is a worse lie than one that fails both
 * ways, so the surfaces must never drift: a new shadow is one enum
 * value, one `open` case and one `content` case, and
 * hosted_proc_shadow_open_stat_lockstep fails CI if only one lands. */
#define TAWCROOT_PROC_SHADOW_NONE        0
#define TAWCROOT_PROC_SHADOW_MAPS        1  /* /proc/<own>/maps */
#define TAWCROOT_PROC_SHADOW_OVERFLOWUID 2  /* /proc/sys/kernel/overflowuid */
#define TAWCROOT_PROC_SHADOW_OVERFLOWGID 3  /* ...overflowgid */
#define TAWCROOT_PROC_SHADOW_PCI_DEVICES 4  /* /proc/bus/pci/devices */
#define TAWCROOT_PROC_SHADOW_STAT        5  /* /proc/stat */
#define TAWCROOT_PROC_SHADOW_VERSION     6  /* /proc/version */
#define TAWCROOT_PROC_SHADOW_UPTIME      7  /* /proc/uptime */
#define TAWCROOT_PROC_SHADOW_LOADAVG     8  /* /proc/loadavg */
#define TAWCROOT_PROC_SHADOW_KIND_MAX    8
int tawcroot_proc_shadow_classify(const char *path);

/* Synthesize the shadow fd for a classified kind. Returns the new fd or
 * a synthesizer -errno; TAWCROOT_PROC_SHADOW_NONE is a caller bug and
 * answers -EINVAL. */
long tawcroot_proc_shadow_open(int kind);

/* Metadata surfaces for a classified kind. `stat`/`statx` describe a
 * procfs regular file (0444, root-owned, size 0) WITHOUT building the
 * memfd — synthesizing content on every stat would re-read and rewrite
 * the whole maps file for an `ls -l /proc/self`. `access` answers 0 for
 * F_OK/R_OK and -EACCES for any W_OK/X_OK bit. */
long tawcroot_proc_shadow_stat(int kind, struct stat *out);
long tawcroot_proc_shadow_statx(int kind, unsigned int mask,
				struct statx *out);
long tawcroot_proc_shadow_access(int kind, int mode);

/* One-pass classification of the /proc magic links the readlink
 * handler synthesizes or post-processes. Strips the /proc/<pid> prefix
 * once and resolves pid ownership lazily (a /proc/<n>/status read) only
 * for the kinds that need it — fd links skip it entirely. The
 * single-kind helpers below are thin wrappers; prefer this in handlers
 * that test more than one kind. */
#define TAWCROOT_PROC_LINK_NONE      0  /* not a magic link we handle */
#define TAWCROOT_PROC_LINK_EXE_SELF  1  /* /proc/<own>/exe */
#define TAWCROOT_PROC_LINK_EXE_OTHER 2  /* /proc/<other-pid>/exe */
#define TAWCROOT_PROC_LINK_CWD       3  /* /proc/<own>/cwd */
#define TAWCROOT_PROC_LINK_FD        4  /* /proc/<any-pid>/fd/<n> */
int tawcroot_proc_link_classify(const char *path);

/* True iff `path` is /proc/self/exe (or /proc/<own-tid>/exe, with an
 * optional task/<tid>/ segment). Used by the readlink handlers for
 * guest-exe synthesis. */
int tawcroot_is_proc_self_exe(const char *path);

/* Finer-grained exe-link classification: distinguishes "/proc/<x>/exe
 * naming OUR process" (synthesize the stashed guest exe path) from
 * "naming some OTHER process" (must NOT be substituted — every tawcroot
 * guest's kernel exe is the same libtawcroot.so, so the readlink-result
 * equality check alone would return the CALLER's guest exe for a
 * different process's link). */
#define TAWCROOT_PROC_EXE_NONE  0  /* not a /proc/<x>/exe path */
#define TAWCROOT_PROC_EXE_SELF  1  /* our pid, or a tid of ours */
#define TAWCROOT_PROC_EXE_OTHER 2  /* a (numeric) pid that isn't ours */
int tawcroot_proc_exe_classify(const char *path);

/* True iff `path` is /proc/self/cwd (or /proc/<own-tid>/cwd, with an
 * optional task/<tid>/ segment). The readlink handler synthesizes the
 * guest cwd via tawcroot_cwd_to_guest_abs — the kernel's link target is
 * the host path, which the guest's world view doesn't contain. */
int tawcroot_is_proc_self_cwd(const char *path);

/* True iff `path` is /proc/self/fd/<n> or /proc/<pid>/fd/<n> (optional
 * task/<tid>/ segment), for ANY pid. The kernel resolves these magic
 * links to HOST paths; the readlink handler reverse-translates the
 * result through the rootfs/bind prefix walk so in-view targets come
 * back as guest paths (outside-view targets pass through verbatim). */
int tawcroot_is_proc_fd_link(const char *path);

/* If `path` is /proc/(self|thread-self|<own pid/tid>)/[task/<tid>/]fd/<n>,
 * returns n; else -1. For triggers that act on the named fd itself. */
int tawcroot_proc_self_fd_num(const char *path);

/* Byte length of the dir/entry magic-link prefix in a /proc-RELATIVE
 * suffix (no leading "/proc/"): (self|thread-self|<pid>)/(task/<tid>/)?
 * then fd/<entry>, map_files/<entry>, cwd, or root. 0 = no match. Used
 * by the read-only-bind stage-2 check (readlink exactly the prefix,
 * RO-prefix-check the target); shares the pid/task grammar with the
 * shadow classifiers above so two matchers can't disagree. */
size_t tawcroot_proc_magic_link_prefix(const char *suf);

/* Same match, plus which containment rule the link falls under (see
 * contain_proc_magic_link / rewrite_own_root in path.c):
 *
 *   FD_OWN    our own fd/<n> or map_files/<e>. NOT contained: the guest
 *             already holds that fd, so the link grants nothing new —
 *             the same line dirfd resolution draws for out-of-view
 *             dirfds. Also what keeps /dev/stdin and /dev/fd/<n> (pipes,
 *             sockets, process substitution) working.
 *   ROOT_OWN  our own root. The kernel resolves it to the HOST root
 *             (tawcroot never chroots), where a real chroot would give
 *             the guest's root — so it is rewritten, not refused.
 *   CONTAIN   everything else (cwd, any other process's link): resolve
 *             through the kernel, then require the target to be in view.
 *
 * Costs one /proc/<n>/status read for a numeric pid that isn't ours,
 * and only after the grammar has matched. */
#define TAWCROOT_PROC_MAGIC_NONE      0
#define TAWCROOT_PROC_MAGIC_FD_OWN    1
#define TAWCROOT_PROC_MAGIC_ROOT_OWN  2
#define TAWCROOT_PROC_MAGIC_CONTAIN   3
size_t tawcroot_proc_magic_link_classify(const char *suf, int *kind);

/* Fast-out for fd-relative accesses: can this relative leaf even
 * compose into a /proc path we shadow? Cheap first-byte test that skips
 * the readlinkat for the vast majority of fd-relative opens and stats. */
int tawcroot_could_be_proc_relative(const char *p);

/* Compose `dirfd`'s host path (via /proc/self/fd/<n>) with a relative
 * guest path into `out`, for re-classifying fd-relative /proc accesses
 * (e.g. openat(proc_dir_fd, "self/maps", ...)). Returns the composed
 * length or -errno. `dirfd` must be a real fd (not AT_FDCWD); the
 * guest path must be relative. */
long tawcroot_compose_fd_relative(int dirfd, const char *gpath_str,
				  char *out, size_t cap);
