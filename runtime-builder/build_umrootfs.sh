#!/bin/bash
# 构建 UML ext4 rootfs（Debian / Ubuntu）：
#   - umrootfs-aarch64.ext4 （Docker 官方 arm64 镜像层转 ext4，免 qemu/binfmt）
#   - metadata.json         （版本 / sha256 / 大小 / 下载 URL，tag uml-<flavor>-subsystem）
# 镜像内置 /umarm-init + /umarm-daemon.sh（命令通道）与 vec0 静态网络配置。
# 用法：SUBSYS_FLAVOR=debian|ubuntu ./build_umrootfs.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FLAVOR="${SUBSYS_FLAVOR:-debian}"
WORK="${WORK:-$(pwd)/umrootfs-work}"
OUT="${OUT:-$(pwd)/umrootfs-out}"
ARCH="${SUBSYS_ARCH:-aarch64}"
GITHUB_REPO="${GITHUB_REPO:-RochelimitDawn/DSHM}"
# ext4 镜像大小：实际内容 ~450MB；1.5G 留足运行时 apt 增长余量，
# 且必须 < 2GB（GitHub release 资产上限，恰好 2147483648 字节会被 422 拒绝）
ROOTFS_SIZE="${ROOTFS_SIZE:-1536M}"

case "$FLAVOR" in
  ubuntu)
    IMAGE="${SUBSYS_IMAGE:-ubuntu:24.04}"
    SUBSYS_TAG="${SUBSYS_TAG:-uml-ubuntu-subsystem}"
    VERSION_LABEL="ubuntu-noble"
    ;;
  *)
    IMAGE="${SUBSYS_IMAGE:-debian:bookworm}"
    SUBSYS_TAG="${SUBSYS_TAG:-uml-debian-subsystem}"
    VERSION_LABEL="debian-bookworm"
    ;;
esac

echo "==> 构建 UML rootfs: $FLAVOR (镜像 $IMAGE, tag $SUBSYS_TAG)"
mkdir -p "$WORK" "$OUT"

if ! command -v skopeo >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq skopeo
fi
if ! command -v mkfs.ext4 >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq e2fsprogs
fi

# ------------------------------------------------------------------ 1. 拉镜像层 → 目录
rootfs="$WORK/rootfs"
rm -rf "$rootfs"; mkdir -p "$rootfs"
imgdir="$WORK/${FLAVOR}-img"
rm -rf "$imgdir"
skopeo copy --override-arch arm64 --override-variant v8 "docker://$IMAGE" "dir:$imgdir" >/dev/null
layer="$(python3 -c "
import json,sys
m=json.load(open('$imgdir/manifest.json'))
for l in m.get('layers', []): print(l['digest'].split(':')[-1]); break
")"
tar -xzf "$imgdir/$layer" -C "$rootfs"

# 精简：清 apt 缓存/日志；DNS 由 umarm-init 写入（umnetx 虚拟 10.0.2.3）
rm -rf "$rootfs/var/cache/apt" "$rootfs/var/lib/apt/lists" "$rootfs/var/log"
# apt 收尾需写 /var/log/apt/eipp.log.xz，目录必须存在
mkdir -p "$rootfs/var/log/apt"
chmod 755 "$rootfs/bin" "$rootfs/sbin" "$rootfs/usr/bin" 2>/dev/null || true

# ------------------------------------------------------------------ 2. 预装常用工具集（qemu binfmt + chroot，需构建机支持 arm64 模拟）
# 工具集：基础（curl/wget/unzip）+ 开发（git/python3/openssh）+ 效率（jq/ripgrep/fd）
# + 网络（dnsutils）；mihomo 由 release 二进制安装（见第 3 段），apt 源默认国内镜像。
PREINSTALL="${PREINSTALL:-1}"
if [ "$PREINSTALL" = "1" ]; then
  if ! command -v qemu-aarch64-static >/dev/null 2>&1; then
    sudo apt-get update -qq
    sudo apt-get install -y -qq qemu-user-static binfmt-support
  fi
  # 国内 apt 源（镜像内 sources.list 覆盖）：Debian TUNA / Ubuntu USTC
  case "$FLAVOR" in
    ubuntu)
      # http（非 https）：minbase 初始无 ca-certificates，https 握手会失败
      APT_MIRROR="http://mirrors.ustc.edu.cn/ubuntu"
      SUITE="noble"
      ;;
    *)
      APT_MIRROR="http://mirrors.tuna.tsinghua.edu.cn/debian"
      SUITE="bookworm"
      ;;
  esac
  mkdir -p "$rootfs/usr/sbin"
  cp /usr/bin/qemu-aarch64-static "$rootfs/usr/sbin/" 2>/dev/null || true
  printf 'deb %s %s main\n' "$APT_MIRROR" "$SUITE" > "$rootfs/etc/apt/sources.list"
  # Docker 层 /tmp 可能无写权限（apt-key 需要临时文件），chroot 前恢复 1777
  chmod 1777 "$rootfs/tmp" 2>/dev/null || mkdir -p "$rootfs/tmp" && chmod 1777 "$rootfs/tmp"
  sudo mount --bind /dev "$rootfs/dev" 2>/dev/null || true
  sudo mount --bind /proc "$rootfs/proc" 2>/dev/null || true
  sudo mount --bind /sys "$rootfs/sys" 2>/dev/null || true
  trap 'sudo umount "$rootfs/dev" "$rootfs/proc" "$rootfs/sys" 2>/dev/null || true' EXIT
  sudo chroot "$rootfs" /usr/bin/env -i \
    DEBIAN_FRONTEND=noninteractive \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/sh -c '
      apt-get update -qq
      apt-get install -y -qq --no-install-recommends \
        curl wget unzip ca-certificates tzdata \
        git python3 python3-pip openssh-client \
        jq ripgrep fd-find bash-completion \
        dnsutils
      apt-get clean
      rm -rf /var/lib/apt/lists/*
    ' || echo "!! 预装工具集失败（构建继续，工具集为空 rootfs）"
  sudo umount "$rootfs/dev" "$rootfs/proc" "$rootfs/sys" 2>/dev/null || true
  trap - EXIT
  rm -f "$rootfs/usr/sbin/qemu-aarch64-static"
fi

# ------------------------------------------------------------------ 3. mihomo 核心 + 内置规则模板 + merge 脚本
# mihomo（Clash.Meta）：MetaCubeX release android-arm64；分流规则模板与合并脚本内置镜像。
MIHOMO_VER="${MIHOMO_VER:-v1.19.32}"
MIHOMO_URL="${MIHOMO_URL:-https://github.com/MetaCubeX/mihomo/releases/download/${MIHOMO_VER}/mihomo-android-arm64-v8-${MIHOMO_VER}.gz}"
mkdir -p "$rootfs/usr/local/bin" "$rootfs/etc/dshm"
if [ ! -f "$rootfs/usr/local/bin/mihomo" ]; then
  echo "==> 下载 mihomo ($MIHOMO_VER)"
  curl -fsSL --retry 3 -o "$WORK/mihomo.gz" "$MIHOMO_URL"
  gunzip -f "$WORK/mihomo.gz"
  install -m 755 "$WORK/mihomo" "$rootfs/usr/local/bin/mihomo"
fi
install -m 644 "$SCRIPT_DIR/patches/clash-rules.yaml" "$rootfs/etc/dshm/clash-rules.yaml"
install -m 755 "$SCRIPT_DIR/launcher/merge-clash-profile.py" "$rootfs/usr/local/bin/merge-clash-profile.py"

# ------------------------------------------------------------------ 4. 内置 umarm init / daemon
install -m 755 "$SCRIPT_DIR/launcher/umarm-init" "$rootfs/umarm-init"
install -m 755 "$SCRIPT_DIR/launcher/umarm-daemon.sh" "$rootfs/umarm-daemon.sh"

# ------------------------------------------------------------------ 5. 转 ext4（mkfs -d 目录预填充，免 root 免 loop）
echo "==> 打包 ext4 ($ROOTFS_SIZE)"
# 彻底卸载 chroot 的 bind mount（残留会导致 mkfs 遍历 /proc /sys）
for m in dev proc sys; do
  sudo umount "$rootfs/$m" 2>/dev/null || true
done
# Docker 层可能带权限受限目录（属主/读位问题），populate 时 chdir 会
# Permission denied；以 root 统一放开读写与遍历位（runner chmod 对
# 非 runner 属主条目无效，必须 sudo）
sudo chmod -R a+rX,a+w "$rootfs" 2>/dev/null || true
# 诊断：列出仍不可读的目录（populate 失败时据此定位）
UNREADABLE="$(sudo find "$rootfs" -maxdepth 3 -type d ! -perm -u+r 2>/dev/null | head -5)"
if [ -n "$UNREADABLE" ]; then
  echo "!! 仍不可读的目录: $UNREADABLE" >&2
fi
img="$OUT/umrootfs-$ARCH-$FLAVOR.ext4"
mkfs.ext4 -q -F -L umarm -O ^has_journal -E "root_owner=0:0" "$img" "$ROOTFS_SIZE" -d "$rootfs"
tune2fs -c 0 -i 0 "$img" 2>/dev/null || true

# ------------------------------------------------------------------ 6. metadata
python3 - "$OUT" "$ARCH" "$GITHUB_REPO" "$SUBSYS_TAG" "$FLAVOR" "$VERSION_LABEL" << 'PY'
import json, hashlib, os, sys, time
out, arch, repo, tag, flavor, version_label = sys.argv[1:7]
name = f"umrootfs-{arch}-{flavor}.ext4"
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
    "rootfsUrl": f"{base}/{name}",
    "rootfsSha256": sha(f"{out}/{name}"),
    "rootfsSizeBytes": os.path.getsize(f"{out}/{name}"),
    "builtAt": time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
}
with open(f"{out}/metadata.json", 'w') as f:
    json.dump(meta, f, indent=2)
print(json.dumps(meta, indent=2))
PY

ls -lh "$OUT/"
