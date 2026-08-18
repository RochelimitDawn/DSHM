#!/bin/bash
# 构建子系统资产（Debian / Ubuntu）：
#   - proot-aarch64.tar.gz        （termux proot + libtalloc + libandroid-shmem）
#   - <flavor>-minbase-aarch64.tar.gz（Docker Hub 官方 arm64 镜像层）
#   - metadata.json               （版本 / sha256 / 大小 / 下载 URL）
# 免 qemu/binfmt：rootfs 直接取 Docker 官方 arm64 镜像层，跨架构无需模拟执行。
# 用法：SUBSYS_FLAVOR=debian|ubuntu SUBSYS_TAG=debian-subsystem ./build_subsystem.sh
set -euo pipefail

FLAVOR="${SUBSYS_FLAVOR:-debian}"
WORK="${WORK:-$(pwd)/subsystem-work}"
OUT="${OUT:-$(pwd)/subsystem-out}"
ARCH="${SUBSYS_ARCH:-aarch64}"
TERMUX_MIRROR="${TERMUX_MIRROR:-https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main}"
GITHUB_REPO="${GITHUB_REPO:-RochelimitDawn/DSHM}"

case "$FLAVOR" in
  ubuntu)
    IMAGE="${SUBSYS_IMAGE:-ubuntu:24.04}"
    SUBSYS_TAG="${SUBSYS_TAG:-ubuntu-subsystem}"
    VERSION_LABEL="ubuntu-noble"
    ;;
  *)
    IMAGE="${SUBSYS_IMAGE:-debian:bookworm}"
    SUBSYS_TAG="${SUBSYS_TAG:-debian-subsystem}"
    VERSION_LABEL="debian-bookworm"
    ;;
esac

echo "==> 构建子系统: $FLAVOR (镜像 $IMAGE, tag $SUBSYS_TAG)"
mkdir -p "$WORK" "$OUT"

# ------------------------------------------------------------------ 1. proot
stage="$WORK/proot-asset"
rm -rf "$stage"; mkdir -p "$stage/usr/bin" "$stage/usr/lib"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$SCRIPT_DIR/fetch_proot_native.sh" "$WORK" "$TERMUX_MIRROR" "$WORK/proot-native" "$ARCH"
mv "$WORK/proot-native/libproot.so" "$stage/usr/bin/proot"
mv "$WORK/proot-native/libtermux-chroot.so" "$stage/usr/bin/termux-chroot"
mv "$WORK/proot-native/libtalloc.so.2"* "$stage/usr/lib/"
mv "$WORK/proot-native/libandroid-shmem.so" "$stage/usr/lib/"
tar -czf "$OUT/proot-$ARCH.tar.gz" -C "$stage" .

# ------------------------------------------------------------------ 2. rootfs
rootfs="$WORK/rootfs"
rm -rf "$rootfs"; mkdir -p "$rootfs"
if ! command -v skopeo >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq skopeo
fi
imgdir="$WORK/${FLAVOR}-img"
rm -rf "$imgdir"
skopeo copy --override-arch arm64 --override-variant v8 "docker://$IMAGE" "dir:$imgdir" >/dev/null
layer="$(python3 -c "
import json,sys
m=json.load(open('$imgdir/manifest.json'))
# 兼容 manifest list 与单 manifest
for l in m.get('layers', []): print(l['digest'].split(':')[-1]); break
")"
tar -xzf "$imgdir/$layer" -C "$rootfs"

# 精简：清 apt 缓存/文档与 resolv.conf（应用侧 proot 绑定自定义 DNS）
rm -rf "$rootfs/var/cache/apt" "$rootfs/var/lib/apt/lists" "$rootfs/var/log"
: > "$rootfs/etc/resolv.conf"
chmod 755 "$rootfs/bin" "$rootfs/sbin" "$rootfs/usr/bin" 2>/dev/null || true

tar -czf "$OUT/${FLAVOR}-minbase-$ARCH.tar.gz" -C "$rootfs" .

# ------------------------------------------------------------------ 3. metadata
python3 - "$OUT" "$ARCH" "$GITHUB_REPO" "$SUBSYS_TAG" "$FLAVOR" "$VERSION_LABEL" << 'PY'
import json, hashlib, os, sys, time
out, arch, repo, tag, flavor, version_label = sys.argv[1:7]
def sha(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(65536), b''):
            h.update(b)
    return h.hexdigest()
base = f"https://github.com/{repo}/releases/download/{tag}"
meta = {
    "version": version_label,
    "flavor": flavor,
    "arch": arch,
    "rootfsUrl": f"{base}/{flavor}-minbase-{arch}.tar.gz",
    "rootfsSha256": sha(f"{out}/{flavor}-minbase-{arch}.tar.gz"),
    "rootfsSizeBytes": os.path.getsize(f"{out}/{flavor}-minbase-{arch}.tar.gz"),
    "builtAt": time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
}
with open(f"{out}/metadata.json", 'w') as f:
    json.dump(meta, f, indent=2)
print(json.dumps(meta, indent=2))
PY

ls -lh "$OUT/"

