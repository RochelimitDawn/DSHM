#!/bin/bash
# 从 Termux 仓库下载 proot 及其依赖，解包后放入指定 native 目录。
# 输出（以 .so 结尾以便打进 jniLibs）：
#   libproot.so / libtermux-chroot.so / libtalloc.so.2.4.3(+软链) / libandroid-shmem.so
set -euo pipefail

WORK="${1:?WORK 目录}"
TERMUX_MIRROR="${2:?TERMUX_MIRROR}"
NATIVE="${3:?native 输出目录}"
ARCH="${4:-aarch64}"

PACKAGES="$WORK/termux-packages.txt"
[ -f "$PACKAGES" ] || curl -fsSL -o "$PACKAGES" "$TERMUX_MIRROR/dists/stable/main/binary-$ARCH/Packages"

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
    curl -fsSL -o "$WORK/$pkg.deb" "$TERMUX_MIRROR/$fn"
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
cp "$TX/lib/libtalloc.so.2.4.3" "$NATIVE/libtalloc.so.2.4.3"
ln -sf "libtalloc.so.2.4.3" "$NATIVE/libtalloc.so.2"
cp "$TX/lib/libandroid-shmem.so" "$NATIVE/libandroid-shmem.so"
# proot loader（PROOT_UNBUNDLE_LOADER 安装于 $PREFIX/libexec/proot/loader）。
# Android 上 app 数据目录 noexec，loader 必须放 nativeLibraryDir 并以
# PROOT_LOADER 环境变量指向（否则 proot 找不到 loader，无法启动任何 guest 进程）。
cp "$TX/libexec/proot/loader" "$NATIVE/libprootloader.so"
cp "$TX/libexec/proot/loader32" "$NATIVE/libprootloader32.so"
echo "    proot 就位: $(ls "$NATIVE" | grep -E 'proot|talloc|shmem' | tr '\n' ' ')"
