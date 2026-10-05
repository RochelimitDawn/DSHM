/* Production-side `tawc_loader_io` impl: forwards to raw syscalls.
 *
 * Mirror of tawcroot/tests/unit/test_loader_map.c's libc-forwarding impl, but
 * built into the freestanding production binary. Runs in `--exec-child`
 * mode and any other path that loads a guest in-process.
 *
 * Lives in PROD_C only — `raw_sys.h` is freestanding and would clash
 * with glibc's syscall wrappers under hosted-glibc tests. The mapper
 * unit tests use the libc-forwarding impl instead.
 */

#include <stddef.h>
#include <stdint.h>

#include "loader_map.h"
#include "raw_sys.h"

/* Linux returns mmap errors in [-4095, -1]. Convert to the (uintptr_t)
 * encoding the loader vtable uses. */
static inline uintptr_t mmap_encode(long rv)
{
	if (rv < 0 && rv >= -4095) return (uintptr_t)(intptr_t)rv;
	return (uintptr_t)rv;
}

static uintptr_t prod_mmap(void *ctx, void *addr, size_t len, int prot,
                           int flags, int fd, uint64_t offset)
{
	(void)ctx;
	long rv = tawc_mmap(addr, len, prot, flags, fd, (long)offset);
	return mmap_encode(rv);
}

static long prod_mprotect(void *ctx, void *addr, size_t len, int prot)
{ (void)ctx; return tawc_mprotect(addr, len, prot); }

static long prod_munmap(void *ctx, void *addr, size_t len)
{ (void)ctx; return tawc_munmap(addr, len); }

static long prod_pread(void *ctx, int fd, void *buf, size_t n, uint64_t off)
{ (void)ctx; return tawc_pread64(fd, buf, n, (long)off); }

const struct tawc_loader_io tawcroot_loader_io_prod = {
	.ctx       = (void *)0,
	.mmap      = prod_mmap,
	.mprotect  = prod_mprotect,
	.munmap    = prod_munmap,
	.pread     = prod_pread,
};
