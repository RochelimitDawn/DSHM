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
  # 进入 arm64 rootfs 用 proot（userspace chroot）+ binfmt qemu：CI runner 容器
  # 无 CAP_SYS_CHROOT，普通 `chroot` 报 "Operation not permitted"，proot 不需要该 capability。
  if ! command -v proot >/dev/null 2>&1; then
    sudo apt-get update -qq
    sudo apt-get install -y -qq proot
  fi
  cp /usr/bin/qemu-aarch64-static "$rootfs/usr/bin/" 2>/dev/null || true
  # proot 内需可用的 DNS：Docker 镜像 layer 的 resolv.conf 常为空/占位，复制宿主
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
  # 镜像选择：镜像自带的 keyring 与某个时间点签名匹配，直接换用当前镜像站的
  # InRelease 会因密钥轮换（NO_PUBKEY）失败。优先用镜像戳定的 Debian snapshot
  # （签名与随镜像的 keyring 一致），SUBSYS_APT_MIRROR 可覆盖为其他源。
  # bookworm 官方用 deb822（sources.list.d/debian.sources），先删除避免与
  # sources.list 并存走 deb.debian.org。默认用 USTC 镜像（快），SUBSYS_APT_MIRROR 可覆盖。
  rm -f "$rootfs/etc/apt/sources.list.d/debian.sources"
  APT_MIRROR="${SUBSYS_APT_MIRROR:-http://mirrors.ustc.edu.cn/debian}"
  APT_SEC="${SUBSYS_APT_SEC_MIRROR:-http://mirrors.ustc.edu.cn/debian-security}"
  echo "    使用 apt 源: $APT_MIRROR"
  cat > "$rootfs/etc/apt/sources.list" <<SLIST
deb $APT_MIRROR bookworm main
deb $APT_MIRROR bookworm-updates main
deb $APT_SEC bookworm-security main
SLIST
  # proot 以真实 uid 执行，dpkg/apt 需要能写 /var/lib/dpkg 等——镜像内这些目录
  # 属 root 600/700，真实用户（CI runner）无权。预烘前把 rootfs 归属当前用户，
  # 预烘后再 chown 回 root:root（保证分发产物属主正确）。
  PREBAKE_UID="$(id -u)"; PREBAKE_GID="$(id -g)"
  if [ "$PREBAKE_UID" = "0" ]; then
    chown -R "$PREBAKE_UID:$PREBAKE_GID" "$rootfs"
  else
    sudo chown -R "$PREBAKE_UID:$PREBAKE_GID" "$rootfs"
  fi
  # 校验 dpkg 数据库确实可写（预烘失败要早暴露）
  test -w "$rootfs/var/lib/dpkg" || test ! -e "$rootfs/var/lib/dpkg" \
    || { echo "!! rootfs/var/lib/dpkg 不可写"; ls -ld "$rootfs/var/lib/dpkg"; exit 4; }
  # 进入 rootfs 执行命令。优先 chroot（配合 rootfs 内 qemu-aarch64-static +
  # binfmt，语义最干净）；CI runner 若非 root，用 sudo chroot。chroot 不可用时
  # 回退 proot -q（userspace，无需 CAP_SYS_CHROOT，但 proot+qemu 组合较脆）。
  SUDO=""
  [ "$(id -u)" != "0" ] && SUDO="sudo"
  ROOTFS_RUN_MODE=""
  if $SUDO chroot "$rootfs" /bin/true 2>/dev/null; then
    ROOTFS_RUN_MODE="chroot"
  elif chroot "$rootfs" /bin/true 2>/dev/null; then
    ROOTFS_RUN_MODE="chroot"
  else
    ROOTFS_RUN_MODE="proot"
  fi
  echo "    rootfs 执行方式: $ROOTFS_RUN_MODE"
  run_rootfs() {
    if [ "$ROOTFS_RUN_MODE" = "chroot" ]; then
      $SUDO chroot "$rootfs" /bin/sh -c "$1"
    else
      proot -q /usr/bin/qemu-aarch64-static -r "$rootfs" -0 -w /tmp \
        -b /dev -b /proc -b /sys -b /dev/pts -b /etc/hosts \
        /bin/sh -c "$1"
    fi
  }
  # apt 的 sandbox 用户（_apt）在 proot 下无法降权，禁用沙箱（容器构建常规做法）
  mkdir -p "$rootfs/etc/apt/apt.conf.d"
  printf 'APT::Sandbox::User "root";\n' > "$rootfs/etc/apt/apt.conf.d/99dsh-nosandbox"
  # proot 以 CI runner 的真实 uid 执行（fake root 不改变文件属主检查），镜像里
  # trusted.gpg.d/keyrings 常为 600 root → apt 报 "not readable ... ignored" →
  # 仓库未签名。统一放开可读。
  chmod 755 "$rootfs/usr/share/keyrings" 2>/dev/null || true
  chmod 644 "$rootfs/usr/share/keyrings/"* 2>/dev/null || true
  chmod 755 "$rootfs/etc/apt/trusted.gpg.d" 2>/dev/null || true
  chmod 644 "$rootfs/etc/apt/trusted.gpg.d/"* 2>/dev/null || true
  # -o Acquire::AllowInsecureRepositories / --allow-unauthenticated：镜像 keyring 与
  # 当前镜像站可能出现密钥轮换（NO_PUBKEY）。预烘产物最终以 sha256 校验分发，
  # 构建期放宽签名仅用于绕过 keyring 陈旧，不降低分发完整性。
  run_rootfs '
    set -e
    export DEBIAN_FRONTEND=noninteractive TMPDIR=/var/tmp
    APT_OPTS="-o Acquire::AllowInsecureRepositories=true -o Acquire::AllowDowngradeToInsecureRepositories=true"
    apt-get $APT_OPTS update -qq
    apt-get $APT_OPTS install -y --allow-unauthenticated --no-install-recommends -qq \
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
  run_rootfs "
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
  # 归属还原为 root:root（分发产物与官方 rootfs 一致；应用侧 proot -0 按 root 解析）
  chown -R 0:0 "$rootfs" 2>/dev/null || sudo chown -R 0:0 "$rootfs"
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

