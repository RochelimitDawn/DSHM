#!/bin/bash
# 构建 UML 内核（linux-um-arm64，LLVM 交叉编译，全静态链接）：
#   - liblinux.so        （UML 内核，注入 APK jniLibs，nativeLibraryDir 为唯一可执行区）
#   - libumarm-stub.so   （syscall stub，stub_exe= 需绝对路径）
# 参考：https://github.com/zalexdev/linux-um-arm64
# 依赖：clang（LLVM=1）、bc/bison/flex/cpio、gcc-aarch64-linux-gnu（aarch64 glibc 头）
# 用法：./build_uml.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${WORK:-$(pwd)/uml-work}"
OUT="${OUT:-$(pwd)/uml-out}"
UML_REPO="${UML_REPO:-https://github.com/zalexdev/linux-um-arm64}"
UML_BRANCH="${UML_BRANCH:-um-arm64}"
JOBS="${JOBS:-$(nproc)}"
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
# 交叉编译遵循上游约定：LLVM=1 + SUBARCH=arm64（Makefile 自动映射
# --target=aarch64-linux-gnu）。user-offsets 需要 aarch64 glibc 头
# （gcc-aarch64-linux-gnu 提供），需在运行环境预装。内核全静态链接
# （CONFIG_STATIC_LINK=y），无动态 libc 依赖，可在 Android 直接 exec。
# -fuse-ld=lld 必须显式指定：clang 对 linux-gnu 目标默认选 GNU ld，
# 而 UML arm64 的 stub 链接带 --no-rosegment（lld 专属参数）。
kmake() { make ARCH=um SUBARCH=arm64 LLVM=1 CC="clang -fuse-ld=lld" "$@"; }
echo "==> 配置内核 (defconfig + $EXTRA_CONFIG)"
kmake defconfig
if [ -f "$EXTRA_CONFIG" ]; then
  while IFS= read -r line; do
    case "$line" in ""|\#*) continue ;; esac
    echo "$line" >> .config
  done < "$EXTRA_CONFIG"
  kmake olddefconfig
fi

# ------------------------------------------------------------------ 3. 编译
echo "==> 编译内核 (-j$JOBS)"
kmake -j"$JOBS"

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
