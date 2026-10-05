#!/bin/bash
# 编译 vendored tawcroot（systrap 方案 proot 替代）为 Android arm64 静态二进制。
# 用法：build_tawcroot.sh <工作目录> <NDK 目录> <输出目录>
# 输出：<输出目录>/libtawcroot.so（.so 结尾以便打进 jniLibs；实际为静态非 PIE ET_EXEC）
set -euo pipefail

WORK="${1:?WORK 目录}"
NDK_DIR="${2:?NDK 目录}"
OUT="${3:?输出目录}"

SRC_DIR="$(cd "$(dirname "$0")" && pwd)/vendor/tawcroot"
CC="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
READER="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
STRIP="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
[ -x "$CC" ] || { echo "::error::NDK clang 不存在: $CC"; exit 1; }

# 上游 build.sh 的 prod 编译参数（不含测试脚手架）。注意：
# - -mno-outline-atomics：NDK r27 默认 armv8-a 下 signal_shadow.c 的 CAS 会被降为
#   outline atomics（compiler-rt，-nostdlib 下不可用），必须内联 LDAXR/STLXR
# - 非 PIE base 0x2000000000（匹配 proot loader 地址）：seccomp 过滤器按 IP 白名单
#   固定 raw syscall 入口，地址跨 re-exec 必须稳定
BASE="0x2000000000"
CFLAGS=(-O2 -fno-strict-aliasing -fno-stack-protector -fvisibility=hidden
  -fno-exceptions -fno-asynchronous-unwind-tables -ffreestanding
  -mno-outline-atomics -Wall -Wextra -Werror -Wframe-larger-than=1024
  -I"$SRC_DIR/include")
LDFLAGS=(-static -nostdlib -nostartfiles
  -Wl,--build-id=none -Wl,-z,noexecstack -Wl,-z,relro
  -Wl,--image-base="$BASE" -Wl,-soname,libtawcroot.so)

BUILD="$WORK/tawcroot-build"
mkdir -p "$BUILD"

SOURCES=$(ls "$SRC_DIR"/src/*.c "$SRC_DIR"/src/arch/aarch64_stub.S "$SRC_DIR"/src/arch/aarch64_loader_jump.S)

OBJS=()
for s in $SOURCES; do
  o="$BUILD/$(basename "$s").o"
  "$CC" "${CFLAGS[@]}" -c "$s" -o "$o"
  OBJS+=("$o")
done

BIN="$BUILD/libtawcroot.so"
"$CC" "${LDFLAGS[@]}" "${OBJS[@]}" -o "$BIN"
"$STRIP" "$BIN"

# 不变量自检：必须 ET_EXEC、无 PT_INTERP、入口落在 base 附近（±1MB）
ETYPE=$("$READER" -h "$BIN" | awk '/Type:/ {print $2}')
ENTRY=$("$READER" -h "$BIN" | awk '/Entry/ {print $4}')
PT_INTERP=$("$READER" -l "$BIN" | awk '/INTERP/ {print "yes"; exit}')
case "$ETYPE" in EXEC*) ;; *) echo "::error::libtawcroot.so 类型为 $ETYPE，预期 EXEC"; exit 1 ;; esac
if [ "$PT_INTERP" = "yes" ]; then echo "::error::libtawcroot.so 含 PT_INTERP（动态链接，seccomp IP 白名单会失效）"; exit 1; fi
BASE_DEC=$((BASE))
if [ "$((ENTRY))" -lt "$BASE_DEC" ] || [ "$((ENTRY))" -gt "$((BASE_DEC + 0x100000))" ]; then
  echo "::error::入口地址 $ENTRY 偏离 base $BASE（非 PIE 不变量失效）"
  exit 1
fi
echo "tawcroot 自检通过: type=$ETYPE entry=$ENTRY size=$(stat -c %s "$BIN")"

mkdir -p "$OUT"
cp -f "$BIN" "$OUT/libtawcroot.so"
echo "输出: $OUT/libtawcroot.so"
