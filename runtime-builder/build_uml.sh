#!/bin/bash
# 构建 UML 内核（linux-um-arm64，bionic 静态链接，Android 应用可直接 exec）：
#   - liblinux.so        （UML 内核，注入 APK jniLibs，nativeLibraryDir 为唯一可执行区）
#   - libumarm-stub.so   （syscall stub，stub_exe= 需绝对路径）
# 参考：https://github.com/zalexdev/linux-um-arm64
# 用法：NDK=/path/to/android-ndk-r27c ./build_uml.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NDK="${NDK:?need android-ndk-r27c (NDK=...)}"
WORK="${WORK:-$(pwd)/uml-work}"
OUT="${OUT:-$(pwd)/uml-out}"
UML_REPO="${UML_REPO:-https://github.com/zalexdev/linux-um-arm64}"
UML_BRANCH="${UML_BRANCH:-um-arm64}"
JOBS="${JOBS:-$(nproc)}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
EXTRA_CONFIG="${EXTRA_CONFIG:-$SCRIPT_DIR/config/uml-base.config}"

mkdir -p "$WORK" "$OUT"
cd "$WORK"

# ------------------------------------------------------------------ 1. 内核源码
if [ ! -d linux/.git ]; then
  echo "==> 克隆 linux-um-arm64 ($UML_BRANCH)"
  git clone --depth 1 -b "$UML_BRANCH" "$UML_REPO" linux
fi
cd linux

# ------------------------------------------------------------------ 2. 配置
echo "==> 配置内核 (bionic defconfig + $EXTRA_CONFIG)"
export PATH="$TOOLCHAIN:$PATH"
make ARCH=um SUBARCH=arm64 LLVM=1 \
  CC="$TOOLCHAIN/aarch64-linux-android31-clang" \
  CROSS_COMPILE=aarch64-linux-android- \
  defconfig
if [ -f "$EXTRA_CONFIG" ]; then
  while IFS= read -r line; do
    case "$line" in ""|\#*) continue ;; esac
    echo "$line" >> .config
  done < "$EXTRA_CONFIG"
  make ARCH=um SUBARCH=arm64 LLVM=1 \
    CC="$TOOLCHAIN/aarch64-linux-android31-clang" \
    CROSS_COMPILE=aarch64-linux-android- \
    olddefconfig
fi

# ------------------------------------------------------------------ 3. 编译
echo "==> 编译内核 (-j$JOBS)"
make ARCH=um SUBARCH=arm64 LLVM=1 -j"$JOBS" \
  CC="$TOOLCHAIN/aarch64-linux-android31-clang" \
  CROSS_COMPILE=aarch64-linux-android-

# ------------------------------------------------------------------ 4. 产物（jniLibs 命名）
echo "==> 输出 jniLibs 产物"
cp linux "$OUT/liblinux.so"
if [ -f linux/stub_exe ]; then
  cp linux/stub_exe "$OUT/libumarm-stub.so"
elif find . -name 'stub_exe' -type f | head -1 | grep -q .; then
  cp "$(find . -name 'stub_exe' -type f | head -1)" "$OUT/libumarm-stub.so"
else
  echo "!! 未找到 stub_exe，运行时需检查 UML 树内路径" >&2
  exit 1
fi
file "$OUT/liblinux.so" "$OUT/libumarm-stub.so"
ls -la "$OUT"
