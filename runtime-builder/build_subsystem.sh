#!/bin/bash
# 构建 Debian 子系统资产：
#   - proot-aarch64.tar.gz        （termux proot + libtalloc + libandroid-shmem）
#   - debian-minbase-aarch64.tar.gz（Docker Hub debian:bookworm arm64 镜像层）
#   - metadata.json               （版本 / sha256 / 大小 / 下载 URL）
# 免 qemu/binfmt：rootfs 直接取 Docker 官方 arm64 镜像层，跨架构无需模拟执行。
set -euo pipefail

WORK="${WORK:-$(pwd)/subsystem-work}"
OUT="${OUT:-$(pwd)/subsystem-out}"
ARCH="${SUBSYS_ARCH:-aarch64}"
TERMUX_MIRROR="${TERMUX_MIRROR:-https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main}"
DEBIAN_IMAGE="${DEBIAN_IMAGE:-debian:bookworm}"
GITHUB_REPO="${GITHUB_REPO:-RochelimitDawn/DSHM}"
SUBSYS_TAG="${SUBSYS_TAG:-debian-subsystem}"

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
skopeo copy --override-arch arm64 --override-variant v8 "docker://$DEBIAN_IMAGE" "dir:$WORK/debian-img" >/dev/null
layer="$(python3 -c "
import json,sys
m=json.load(open('$WORK/debian-img/manifest.json'))
# 兼容 manifest list 与单 manifest
for l in m.get('layers', []): print(l['digest'].split(':')[-1]); break
")"
tar -xzf "$WORK/debian-img/$layer" -C "$rootfs"

# 精简：清 apt 缓存/文档与 resolv.conf（应用侧 proot 绑定自定义 DNS）
rm -rf "$rootfs/var/cache/apt" "$rootfs/var/lib/apt/lists" "$rootfs/var/log"
: > "$rootfs/etc/resolv.conf"
chmod 755 "$rootfs/bin" "$rootfs/sbin" "$rootfs/usr/bin" 2>/dev/null || true

# zip 打包（应用侧用内置 ZipInputStream 解压）
(cd "$rootfs" && zip -r -q "$OUT/debian-minbase-$ARCH.zip" .)

# ------------------------------------------------------------------ 3. metadata
python3 - "$OUT" "$ARCH" "$GITHUB_REPO" "$SUBSYS_TAG" << 'PY'
import json, hashlib, os, sys, time
out, arch, repo, tag = sys.argv[1:5]
def sha(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(65536), b''):
            h.update(b)
    return h.hexdigest()
base = f"https://github.com/{repo}/releases/download/{tag}"
meta = {
    "version": "debian-bookworm",
    "arch": arch,
    "rootfsUrl": f"{base}/debian-minbase-{arch}.zip",
    "rootfsSha256": sha(f"{out}/debian-minbase-{arch}.zip"),
    "rootfsSizeBytes": os.path.getsize(f"{out}/debian-minbase-{arch}.zip"),
    "builtAt": time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
}
with open(f"{out}/metadata.json", 'w') as f:
    json.dump(meta, f, indent=2)
print(json.dumps(meta, indent=2))
PY

ls -lh "$OUT/"
