#!/usr/bin/env bash
# 交叉编译 node-pty for Android arm64（bionic）。
# 用法: build_node_pty.sh <node-version 如 v22.19.0>
set -euo pipefail

NODE_VER="${1:-v22.19.0}"
NDK_VER="${NDK_VER:-r27c}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# NDK 缓存目录：默认不复用（每次干净工作区）；设 NDK_CACHE_DIR 可跨次复用，
# 避免重复下载 ~1GB NDK（迭代 pty.node 时尤其有用）。
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> 准备 Android NDK ${NDK_VER}"
if [ -n "${NDK_CACHE_DIR:-}" ] && [ -d "$NDK_CACHE_DIR/android-ndk-${NDK_VER}" ]; then
  echo "    复用缓存 $NDK_CACHE_DIR/android-ndk-${NDK_VER}"
  NDK_ROOT="$NDK_CACHE_DIR/android-ndk-${NDK_VER}"
else
  curl -sL -o "$WORK/ndk.zip" "https://dl.google.com/android/repository/android-ndk-${NDK_VER}-linux.zip"
  (cd "$WORK" && unzip -q ndk.zip)
  NDK_ROOT="$WORK/android-ndk-${NDK_VER}"
  if [ -n "${NDK_CACHE_DIR:-}" ]; then
    mkdir -p "$NDK_CACHE_DIR"
    cp -r "$NDK_ROOT" "$NDK_CACHE_DIR/" 2>/dev/null || true
  fi
fi
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"

echo "==> 下载 Node headers ${NODE_VER}"
if ! curl -fsSL -o "$WORK/node-headers.tar.gz" "${NODE_MIRROR:-https://nodejs.org/dist}/${NODE_VER}/node-${NODE_VER}-headers.tar.gz"; then
  echo "    [warn] 主源下载失败，回退 nodejs.org 官方源"
  curl -fsSL -o "$WORK/node-headers.tar.gz" "https://nodejs.org/dist/${NODE_VER}/node-${NODE_VER}-headers.tar.gz"
fi
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
# TS2591：npm 10 对本地目录依赖跳过 devDeps（tsc/@types/node），显式安装并声明 node types
# TS2593：types 数组声明 node 后会排除其它 @types（describe/it）——测试文件需要 mocha
# TS18046：catch 变量 unknown 类型（useUnknownInCatchVariables，随 types 显式化暴露）
(cd "$WORK/pty" && npm install typescript@4.9.5 @types/node @types/mocha --no-save --no-audit --no-fund >/dev/null 2>&1)
sed -i 's/"compilerOptions": {/"compilerOptions": {\n    "types": ["node", "mocha"],\n    "useUnknownInCatchVariables": false,/' "$WORK/pty/src/tsconfig.json"

echo "==> 交叉编译"
export npm_config_arch=arm64
export npm_config_platform=android
export npm_config_nodedir="$WORK/node-headers"
export npm_config_build_from_source=true
export CXXFLAGS="-std=c++17 -O2 -Wno-psabi"
export CFLAGS="-O2 -Wno-psabi"
# 静态链接 NDK 的 libc++：默认动态链接会在 pty.node 留下
# DT_NEEDED libc++_shared.so，而该库不在设备上（应用私有 lib 目录也不含），
# require('node-pty') 直接 "libc++_shared.so: cannot open shared object file"
# → 原生模块加载失败 → 终端不可用。静态化后 pty.node 无 libc++ 依赖，
# 只需 bionic 系统库（libc/libm/libdl），零额外分发。node-gyp 通过
# LDFLAGS 传给 clang 链接阶段。
export LDFLAGS="-static-libstdc++"
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

# 硬校验：pty.node 不得依赖 libc++_shared.so（设备无此库，加载即失败）
NEEDED="$("$TOOLCHAIN/bin/llvm-readelf" -d "$PTY_NODE" | grep -o '\[libc++_shared.so\]' || true)"
if [ -n "$NEEDED" ]; then
  echo "!! pty.node 仍依赖 libc++_shared.so（静态链接未生效）" >&2
  echo "   NEEDED: $("$TOOLCHAIN/bin/llvm-readelf" -d "$PTY_NODE" | grep NEEDED)" >&2
  exit 3
fi
echo "    libc++ 静态链接校验通过（无 libc++_shared.so 依赖）"

OUT_DIR="${PTY_OUT_DIR:-/tmp/pty-out}"
mkdir -p "$OUT_DIR"
cp "$PTY_NODE" "$OUT_DIR/pty.node"
echo "==> 产物: $OUT_DIR/pty.node"
