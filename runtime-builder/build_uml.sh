#!/bin/bash
# 构建 UML 内核（linux-um-arm64，LLVM 交叉编译，全静态链接）：
#   - liblinux.so            （UML 内核，注入 APK jniLibs，nativeLibraryDir 为唯一可执行区）
#   - libumarm-stub.so       （syscall stub，stub_exe= 需绝对路径）
#   - kernel-src-hash.txt    （内核源哈希，CI 复用判断用）
# 参考：https://github.com/zalexdev/linux-um-arm64
# 依赖：clang 17+（LLVM=1）、lld/llvm、bc/bison/flex/cpio、gcc-aarch64-linux-gnu（aarch64 glibc 头）
# 用法：./build_uml.sh
#       HASH_ONLY=1 ./build_uml.sh   # 只克隆并输出源哈希（复用判断）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${WORK:-$(pwd)/uml-work}"
OUT="${OUT:-$(pwd)/uml-out}"
UML_REPO="${UML_REPO:-https://github.com/zalexdev/linux-um-arm64}"
UML_BRANCH="${UML_BRANCH:-um-arm64}"
JOBS="${JOBS:-$(nproc)}"
EXTRA_CONFIG="${EXTRA_CONFIG:-$SCRIPT_DIR/config/uml-base.config}"

# ------------------------------------------------------------------ 1. 内核源码
ensure_clone() {
  mkdir -p "$WORK"
  if [ ! -d "$WORK/linux/.git" ]; then
    echo "==> 克隆 linux-um-arm64 ($UML_BRANCH)" >&2
    git clone --depth 1 -b "$UML_BRANCH" "$UML_REPO" "$WORK/linux"
  fi
}
ensure_clone

# 源哈希：内核 HEAD + 配置片段 + umnetx 脚本（CI 复用判断：一致则跳过重编）
compute_hash() {
  local head config umnetx
  head=$(git -C "$WORK/linux" rev-parse HEAD)
  config=$(sha256sum "$EXTRA_CONFIG" | cut -d' ' -f1)
  umnetx=$(sha256sum "$SCRIPT_DIR/build_umnetx.sh" | cut -d' ' -f1)
  printf '%s\n' "$head-$config-$umnetx"
}

KERNEL_HASH=$(compute_hash)

# HASH_ONLY 模式：只输出哈希（CI 复用判断），不编译
if [ "${HASH_ONLY:-0}" = "1" ]; then
  printf '%s\n' "$KERNEL_HASH"
  exit 0
fi

mkdir -p "$OUT"
cd "$WORK/linux"

# ------------------------------------------------------------------ 2. 配置
# 交叉编译遵循上游约定：LLVM=1 + SUBARCH=arm64（Makefile 自动映射
# --target=aarch64-linux-gnu）。user-offsets 需要 aarch64 glibc 头
# （gcc-aarch64-linux-gnu 提供）。内核全静态链接（CONFIG_STATIC_LINK=y）。
# 注意：-fuse-ld=lld 不能经 CC 传入——纯编译步骤它是 unused argument，
# kbuild 在 CC 之后追加 -Werror（last-wins）会覆盖 -Wno 抑制。改为在
# 源码级给 stub 链接规则追加（见第 3 段），CC 保持干净。
echo "==> 配置内核 (defconfig + $EXTRA_CONFIG)" >&2
make ARCH=um SUBARCH=arm64 LLVM=1 defconfig
if [ -f "$EXTRA_CONFIG" ]; then
  while IFS= read -r line; do
    case "$line" in ""|\#*) continue ;; esac
    echo "$line" >> .config
  done < "$EXTRA_CONFIG"
  make ARCH=um SUBARCH=arm64 LLVM=1 olddefconfig
fi

# ------------------------------------------------------------------ 3. 源码补丁 + 编译
# stub 链接（$(CC) -nostdlib ...）由 clang 选择链接器：对 linux-gnu 目标
# 默认选 GNU ld，而 STUB_EXE_LDFLAGS 的 --no-rosegment 是 lld 专属参数。
# 给 STUB_EXE_LDFLAGS 追加 -fuse-ld=lld 强制 lld（vmlinux 链接走
# LLVM=1 的 ld.lld，无需处理）。
if ! grep -q "fuse-ld=lld" arch/um/kernel/skas/Makefile; then
  sed -i 's/^STUB_EXE_LDFLAGS = -Wl,-n -Wl,--no-rosegment -static$/STUB_EXE_LDFLAGS = -Wl,-n -Wl,--no-rosegment -static -fuse-ld=lld/' arch/um/kernel/skas/Makefile
  echo "==> 已补丁 stub 链接（-fuse-ld=lld）" >&2
fi

echo "==> 编译内核 (-j$JOBS)" >&2
make ARCH=um SUBARCH=arm64 LLVM=1 -j"$JOBS"

# ------------------------------------------------------------------ 4. 产物（jniLibs 命名）
echo "==> 输出 jniLibs 产物" >&2
cp linux "$OUT/liblinux.so"
# stub 产物：优先剥离后的 stub_exe；仅有 stub_exe.dbg（调试版）时改名复制
STUB_PATH=""
if [ -f linux/stub_exe ]; then
  STUB_PATH="linux/stub_exe"
elif [ -f arch/um/kernel/skas/stub_exe.dbg ]; then
  STUB_PATH="arch/um/kernel/skas/stub_exe.dbg"
else
  STUB_PATH="$(find . -maxdepth 4 -name 'stub_exe*' -type f | head -1 || true)"
fi
if [ -n "$STUB_PATH" ] && [ -f "$STUB_PATH" ]; then
  cp "$STUB_PATH" "$OUT/libumarm-stub.so"
else
  echo "!! 未找到 stub_exe，运行时需检查 UML 树内路径" >&2
  exit 1
fi
printf '%s\n' "$KERNEL_HASH" > "$OUT/kernel-src-hash.txt"
file "$OUT/liblinux.so" "$OUT/libumarm-stub.so"
ls -la "$OUT"
