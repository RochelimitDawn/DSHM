#!/bin/bash
# 构建子系统资产（Debian）：
#   - proot-aarch64.tar.gz        （termux proot + libtalloc + libandroid-shmem）
#   - <flavor>-minbase-aarch64.tar.gz（Docker Hub 官方 arm64 镜像层）
#   - metadata.json               （版本 / sha256 / 大小 / 下载 URL）
# 免 qemu/binfmt：rootfs 直接取 Docker 官方 arm64 镜像层，跨架构无需模拟执行。
# 用法：SUBSYS_FLAVOR=debian SUBSYS_TAG=debian-subsystem ./build_subsystem.sh
set -euo pipefail

FLAVOR="${SUBSYS_FLAVOR:-debian}"
WORK="${WORK:-$(pwd)/subsystem-work}"
OUT="${OUT:-$(pwd)/subsystem-out}"
ARCH="${SUBSYS_ARCH:-aarch64}"
TERMUX_MIRROR="${TERMUX_MIRROR:-https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main}"
GITHUB_REPO="${GITHUB_REPO:-RochelimitDawn/DSHM}"

# 仅 Debian：Ubuntu flavor 已移除（proot/UML 统一 Debian 镜像包，发行版切换下线）
# Docker Hub 在部分网络下不可达，SUBSYS_IMAGE_MIRROR 指定镜像站前缀
# （如 docker.m.daocloud.io）。空则直连 docker.io。
IMAGE_MIRROR="${SUBSYS_IMAGE_MIRROR:-}"
IMAGE="${SUBSYS_IMAGE:-debian:bookworm}"
IMAGE_REF="${IMAGE_MIRROR:+$IMAGE_MIRROR/}${IMAGE}"
SUBSYS_TAG="${SUBSYS_TAG:-debian-subsystem}"
VERSION_LABEL="debian-bookworm"

echo "==> 构建子系统: $FLAVOR (镜像 $IMAGE_REF, tag $SUBSYS_TAG)"
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
skopeo copy --override-arch arm64 --override-variant v8 "docker://$IMAGE_REF" "dir:$imgdir" >/dev/null
layer="$(python3 -c "
import json,sys
m=json.load(open('$imgdir/manifest.json'))
# 兼容 manifest list 与单 manifest
for l in m.get('layers', []): print(l['digest'].split(':')[-1]); break
")"
tar -xzf "$imgdir/$layer" -C "$rootfs"

# ------------------------------------------------------------------ 2.5 预装工具链（构建期 apt）
# 设备侧不再依赖联网 apt：curl/wget/jq/unzip/xz/zstd/zip 直接进 rootfs。
# 通过 qemu-aarch64-static + chroot 在 amd64 构建机上安装 arm64 包。
# 关闭 dpkg fsync 与 docs/man/locale，控制体积与安装耗时（与设备侧 dpkg 配置一致）。
NODE_VERSION="${SUBSYS_NODE_VERSION:-v22.20.0}"
NPM_REGISTRY="${SUBSYS_NPM_REGISTRY:-https://registry.npmmirror.com}"
NODE_MIRROR="${SUBSYS_NODE_MIRROR:-https://registry.npmmirror.com/-/binary/node}"

if [ "${SUBSYS_SKIP_PREBAKE:-0}" != "1" ]; then
  echo "==> 预装 rootfs 工具链（apt: curl/wget/jq/unzip/xz/zstd/zip + node/pnpm/tsc/esbuild）"
  cp /usr/bin/qemu-aarch64-static "$rootfs/usr/bin/"
  # chroot 内需可用的 DNS：Docker 镜像 layer 的 resolv.conf 常为空/占位，复制宿主
  cp /etc/resolv.conf "$rootfs/etc/resolv.conf" 2>/dev/null || true
  mkdir -p "$rootfs/var/log/apt" "$rootfs/var/cache/apt/archives/partial" "$rootfs/var/tmp"
  chmod 1777 "$rootfs/var/tmp"; chmod 755 "$rootfs/var/cache/apt"
  # dpkg 提速（与设备侧 AddonManager 同款）
  mkdir -p "$rootfs/etc/dpkg/dpkg.cfg.d"
  cat > "$rootfs/etc/dpkg/dpkg.cfg.d/99dsh-fast" <<'DCFG'
force-unsafe-io
path-exclude=/usr/share/doc/*
path-exclude=/usr/share/man/*
path-exclude=/usr/share/locale/*
DCFG
  # apt 缓存归档持久化（与设备侧 99dsh-tmp 一致）
  mkdir -p "$rootfs/etc/apt/apt.conf.d"
  echo 'Dir::Cache::archives "/var/cache/apt/archives";' \
    > "$rootfs/etc/apt/apt.conf.d/99dsh-tmp"
  # 国内镜像（bookworm + security），加速构建。minbase 尚无 ca-certificates，
  # 首轮用 http，装好 ca-certificates 后再切 https（见下方）。
  cat > "$rootfs/etc/apt/sources.list" <<'SLIST'
deb http://mirrors.ustc.edu.cn/debian bookworm main
deb http://mirrors.ustc.edu.cn/debian bookworm-updates main
deb http://mirrors.ustc.edu.cn/debian-security bookworm-security main
SLIST
  chroot "$rootfs" /usr/bin/qemu-aarch64-static /bin/bash -c '
    set -e
    export DEBIAN_FRONTEND=noninteractive TMPDIR=/var/tmp
    apt-get update -qq
    apt-get install -y --no-install-recommends -qq \
      curl wget jq unzip xz-utils zstd zip ca-certificates
    apt-get clean
  '
  # ---------------------------------------------------------- node + npm globals
  # 子系统 node 固定 linux-arm64，装进 /opt/node；pnpm/typescript/esbuild 全局装到 /opt/node
  echo "==> 预装 rootfs node $NODE_VERSION + pnpm/tsc/esbuild"
  nodeWork="$WORK/node-prebake"; rm -rf "$nodeWork"; mkdir -p "$nodeWork"
  nodeTar="$nodeWork/node.tar.gz"
  curl -fsSL --retry 3 -o "$nodeTar" \
    "$NODE_MIRROR/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.gz"
  rm -rf "$rootfs/opt/node"; mkdir -p "$rootfs/opt/node"
  tar -xzf "$nodeTar" --strip-components=1 -C "$rootfs/opt/node"
  chroot "$rootfs" /usr/bin/qemu-aarch64-static /bin/bash -c "
    set -e
    export PATH=/opt/node/bin:\$PATH
    export npm_config_registry='$NPM_REGISTRY'
    export npm_config_update_notifier=false
    /opt/node/bin/npm install -g --prefix /opt/node --no-audit --no-fund \
      pnpm typescript esbuild
    /opt/node/bin/node -v
    /opt/node/bin/pnpm -v
    /opt/node/bin/tsc -v
    /opt/node/bin/esbuild --version
  "
  # 清理 qemu 与 apt 缓存（qemu 只在构建期需要，不进分发产物）
  rm -f "$rootfs/usr/bin/qemu-aarch64-static"
fi

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

