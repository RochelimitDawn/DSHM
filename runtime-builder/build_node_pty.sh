#!/usr/bin/env bash
# 交叉编译 node-pty for Android arm64（bionic）。
# 用法: build_node_pty.sh <node-version 如 v22.19.0>
set -euo pipefail

NODE_VER="${1:-v22.19.0}"
NDK_VER="${NDK_VER:-r27c}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> 下载 Android NDK ${NDK_VER}"
curl -sL -o "$WORK/ndk.zip" "https://dl.google.com/android/repository/android-ndk-${NDK_VER}-linux.zip"
(cd "$WORK" && unzip -q ndk.zip)
NDK_ROOT="$WORK/android-ndk-${NDK_VER}"
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"

echo "==> 下载 Node headers ${NODE_VER}"
curl -sL -o "$WORK/node-headers.tar.gz" "https://nodejs.org/dist/${NODE_VER}/node-${NODE_VER}-headers.tar.gz"
mkdir -p "$WORK/node-headers"
tar -xzf "$WORK/node-headers.tar.gz" -C "$WORK/node-headers" --strip-components=1

echo "==> 获取 node-pty 源码"
(cd "$WORK" && npm pack node-pty@1.1.0 >/dev/null 2>&1)
mkdir -p "$WORK/pty"
tar -xzf "$WORK/node-pty-1.1.0.tgz" -C "$WORK/pty" --strip-components=1

echo "==> 应用 bionic 补丁"
# Android bionic 无 <pty.h>/openpty/forkpty，注入 posix_openpt 兼容实现
cp "$SCRIPT_DIR/patches/pty_compat.h" "$WORK/pty/src/unix/pty_compat.h"
sed -i 's|#include <pty.h>|#include "pty_compat.h"|g' "$WORK/pty/src/unix/pty.cc"
# bionic 无 libutil.so，去掉 -lutil（openpty/forkpty 由 pty_compat.h 提供）
sed -i "/'-lutil'/d" "$WORK/pty/binding.gyp"
# 现代 TypeScript 已移除 target=es5，node-pty 1.1.0 的 build 脚本会失败
sed -i 's/"target": "es5"/"target": "es2022"/' "$WORK/pty/src/tsconfig.json"

echo "==> 交叉编译"
export npm_config_arch=arm64
export npm_config_platform=android
export npm_config_nodedir="$WORK/node-headers"
export npm_config_build_from_source=true
export CXXFLAGS="-std=c++17 -O2 -Wno-psabi"
export CFLAGS="-O2 -Wno-psabi"
mkdir -p "$WORK/build"
(cd "$WORK/build" && npm init -y >/dev/null 2>&1)
(cd "$WORK/build" && \
  CC="$TOOLCHAIN/bin/aarch64-linux-android31-clang" \
  CXX="$TOOLCHAIN/bin/aarch64-linux-android31-clang++" \
  AR="$TOOLCHAIN/bin/llvm-ar" \
  npm install "$WORK/pty" --no-save --build-from-source 2>&1 | tail -5)

PTY_NODE="$WORK/build/node_modules/node-pty/build/Release/pty.node"
if [ ! -f "$PTY_NODE" ]; then
  echo "!! node-pty 编译失败" >&2
  exit 2
fi

echo "==> 验证架构"
"$TOOLCHAIN/bin/llvm-readelf" -h "$PTY_NODE" | grep -E "Machine|Class" || true

OUT_DIR="${PTY_OUT_DIR:-/tmp/pty-out}"
mkdir -p "$OUT_DIR"
cp "$PTY_NODE" "$OUT_DIR/pty.node"
echo "==> 产物: $OUT_DIR/pty.node"
