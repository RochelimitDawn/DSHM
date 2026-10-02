#!/bin/bash
# 从 Termux 仓库下载 proot 及其依赖，解包后放入指定 native 目录。
# 输出（以 .so 结尾以便打进 jniLibs）：
#   libproot.so / libtermux-chroot.so / libtalloc.so.2.x(+软链，版本号随仓库漂移按通配解析) / libandroid-shmem.so
set -euo pipefail

WORK="${1:?WORK 目录}"
TERMUX_MIRROR="${2:?TERMUX_MIRROR}"
NATIVE="${3:?native 输出目录}"
ARCH="${4:-aarch64}"

# 镜像链（清华 TUNA 屏蔽 GitHub Actions 数据中心 IP 返回 403，逐级回退官方）
MIRRORS=()
IFS=';' read -ra MIRRORS <<< "$TERMUX_MIRROR"
MIRRORS+=("https://packages.termux.dev/apt/termux-main" "https://mirrors.ustc.edu.cn/termux/apt/termux-main")

fetch_url() { # path out
  local path="$1" out="$2" m
  for m in "${MIRRORS[@]}"; do
    if curl -fsSL --max-time 120 -o "$out" "${m%/}/$path"; then return 0; fi
  done
  echo "::error::全部镜像拉取失败: $path"; return 1
}

PACKAGES="$WORK/termux-packages.txt"
[ -f "$PACKAGES" ] || fetch_url "dists/stable/main/binary-$ARCH/Packages" "$PACKAGES"

fetch_deb() { # pkg
  local pkg="$1"
  if [ ! -f "$WORK/$pkg.deb" ]; then
    local fn
    fn="$(python3 -c "
import re,sys
data=open('$PACKAGES').read()
for p in data.split('\n\n'):
    if re.search(r'^Package: $pkg\$', p, re.M):
        m=re.search(r'^Filename: (.+)\$', p, re.M)
        if m: print(m.group(1)); break
")"
    if [ -z "$fn" ]; then echo "::error::termux 仓库未找到 $pkg"; exit 1; fi
    fetch_url "$fn" "$WORK/$pkg.deb"
  fi
}

STAGE="$WORK/proot-stage"
mkdir -p "$STAGE" "$NATIVE"
for p in proot libtalloc libandroid-shmem; do
  fetch_deb "$p"
  dpkg-deb -x "$WORK/$p.deb" "$STAGE/"
done

TX="$STAGE/data/data/com.termux/files/usr"
cp "$TX/bin/proot" "$NATIVE/libproot.so"
cp "$TX/bin/termux-chroot" "$NATIVE/libtermux-chroot.so"
# libtalloc 精确版本号随 termux 仓库更新漂移（2.4.3 → 2.4.4 等），按通配解析实际文件
TALLOC_FILE="$(find "$TX/lib" -maxdepth 1 -name 'libtalloc.so.2.*' ! -type l | head -1)"
if [ -z "$TALLOC_FILE" ]; then echo "::error::termux proot deb 未找到 libtalloc"; exit 1; fi
TALLOC_NAME="$(basename "$TALLOC_FILE")"
cp "$TALLOC_FILE" "$NATIVE/$TALLOC_NAME"
ln -sf "$TALLOC_NAME" "$NATIVE/libtalloc.so.2"
cp "$TX/lib/libandroid-shmem.so" "$NATIVE/libandroid-shmem.so"
# proot loader（PROOT_UNBUNDLE_LOADER 安装于 $PREFIX/libexec/proot/loader）。
# Android 上 app 数据目录 noexec，loader 必须放 nativeLibraryDir 并以
# PROOT_LOADER 环境变量指向（否则 proot 找不到 loader，无法启动任何 guest 进程）。
cp "$TX/libexec/proot/loader" "$NATIVE/libprootloader.so"
cp "$TX/libexec/proot/loader32" "$NATIVE/libprootloader32.so"
echo "    proot 就位: $(ls "$NATIVE" | grep -E 'proot|talloc|shmem' | tr '\n' ' ')"
