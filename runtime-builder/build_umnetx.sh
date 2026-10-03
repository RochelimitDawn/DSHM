#!/bin/bash
# 构建 umnetx（用户态网络栈，UML ↔ 外网中继，零特权）：
#   - libumnetx.so  （注入 APK jniLibs；AF_UNIX bess 通道 + 宿主 socket 中继）
# 参考：https://github.com/AAQWQ11/umnetx
# 用法：NDK=/path/to/android-ndk-r27c ./build_umnetx.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NDK="${NDK:?need android-ndk-r27c (NDK=...)}"
WORK="${WORK:-$(pwd)/umnetx-work}"
OUT="${OUT:-$(pwd)/umnetx-out}"
UMNETX_REPO="${UMNETX_REPO:-https://github.com/AAQWQ11/umnetx}"
UMNETX_REF="${UMNETX_REF:-main}"

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CXX="$TOOLCHAIN/aarch64-linux-android31-clang++"

mkdir -p "$WORK" "$OUT"
cd "$WORK"

if [ ! -f umnetx.cpp ]; then
  echo "==> 拉取 umnetx 源码 ($UMNETX_REF)"
  for f in umnetx.cpp README.md; do
    curl -fsSL --retry 3 -o "$f" "$UMNETX_REPO/raw/$UMNETX_REF/$f"
  done
fi

echo "==> 编译 umnetx (aarch64, 静态自包含)"
# 静态链接：Android 无 /system/lib64 libc++_shared，nativeLibraryDir 执行需自包含
"$CXX" -std=c++17 -O2 -static -o "$OUT/libumnetx.so" umnetx.cpp
file "$OUT/libumnetx.so"
