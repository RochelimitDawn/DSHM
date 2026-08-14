#!/usr/bin/env bash
# 装配 SiliconLeap Android 运行时（在 CI 的 x64 Linux 上为 arm64 生成 runtime.zip）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${WORK:-/tmp/sl-runtime}"
OUT="${OUT:-$SCRIPT_DIR/out}"
ARCH="${ARCH:-aarch64}"
TERMUX_APP_VER="${TERMUX_APP_VER:-v0.118.3}"
DSH_VERSION="${DSH_VERSION:-0.1.0-rc.6}"
NODE_VER="${NODE_VER:-v22.19.0}"
BUILD_PTY="${BUILD_PTY:-1}"

mkdir -p "$WORK" "$OUT"
PREFIX="$WORK/prefix/usr"

echo "==> [1/6] 获取 Termux bootstrap"
if [ ! -f "$WORK/bootstrap-aarch64.zip" ]; then
  curl -sL -o "$WORK/bootstrap-aarch64.zip" \
    "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.02.12-r1%2Bapt.android-7/bootstrap-aarch64.zip"
fi
rm -rf "$WORK/bootstrap"
mkdir -p "$WORK/bootstrap"
(cd "$WORK" && unzip -o -q bootstrap-aarch64.zip -d "$WORK/bootstrap")
rm -rf "$PREFIX"
mkdir -p "$PREFIX"
if [ -d "$WORK/bootstrap/usr" ]; then
  cp -r "$WORK/bootstrap/usr/." "$PREFIX/"
else
  cp -r "$WORK/bootstrap/." "$PREFIX/"
fi
# 按 SYMLINKS.txt 重建符号链接。
# 官方格式（termux-app TermuxInstaller）：
#   parts[0] = 链接目标  parts[1] = 链接位置（相对 prefix 根，如 ./bin/zstdmt）
#   - 目标是绝对路径（旧前缀 /data/data/...）→ 重定位为相对链接
#   - 目标是相对路径（如 zstd）→ 直接使用（按标准语义相对链接所在目录解析）
if [ -f "$PREFIX/SYMLINKS.txt" ]; then
  python3 - "$PREFIX" <<'PY'
import os, sys
prefix = sys.argv[1]
OLD = "/data/data/com.termux/files/usr"
created = 0
for raw in open(os.path.join(prefix, "SYMLINKS.txt"), encoding="utf-8"):
    line = raw.rstrip("\n")
    if "←" not in line:
        continue
    target, link_rel = line.split("←", 1)
    link_rel = link_rel.lstrip("./")
    link_path = os.path.join(prefix, link_rel)
    os.makedirs(os.path.dirname(link_path), exist_ok=True)
    if os.path.lexists(link_path):
        os.unlink(link_path)
    if target.startswith(OLD):
        real_target = os.path.join(prefix, target[len(OLD):].lstrip("/"))
        link_to = os.path.relpath(real_target, os.path.dirname(link_path))
    else:
        link_to = target
    os.symlink(link_to, link_path)
    created += 1
os.remove(os.path.join(prefix, "SYMLINKS.txt"))
print(f"    已重建 {created} 个符号链接")
PY
fi
echo "    bootstrap 顶层: $(ls "$PREFIX" | tr '\n' ' ')"

echo "==> [2/6] 解析并下载 Termux 包（nodejs/ripgrep/git/bash）"
export DEB_CACHE="$WORK/debs"
python3 "$SCRIPT_DIR/deps.py" nodejs ripgrep git bash "$PREFIX"
python3 - "$PREFIX/versions.json" <<'PY'
import json, sys
v = json.load(open(sys.argv[1]))
print("    nodejs =", v.get("nodejs"), " ripgrep =", v.get("ripgrep"))
PY

echo "==> [3/6] 安装 @deepseek-ai/dsh@${DSH_VERSION}"
npm install --prefix "$PREFIX/lib" "@deepseek-ai/dsh@${DSH_VERSION}" \
  --omit=dev --ignore-scripts --no-audit --no-fund
test -f "$PREFIX/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"

echo "==> [4/6] node-pty Android 编译"
if [ "$BUILD_PTY" = "1" ]; then
  PTY_OUT_DIR="$WORK/pty-out" bash "$SCRIPT_DIR/build_node_pty.sh" "$NODE_VER" \
    || echo "    [warn] node-pty 编译失败，将继续（PTY 将降级不可用）"
  if [ -f "$WORK/pty-out/pty.node" ]; then
    mkdir -p "$PREFIX/lib/node_modules/node-pty/build/Release"
    cp "$WORK/pty-out/pty.node" "$PREFIX/lib/node_modules/node-pty/build/Release/pty.node"
    echo "    node-pty 已就位"
  fi
else
  echo "    [skip] BUILD_PTY=0"
fi

echo "==> [5/6] 应用运行时补丁"
node "$SCRIPT_DIR/patch_runtime.js" "$PREFIX"

echo "==> [6/6] 生成 runtime.zip"
STAGE="$WORK/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -rL "$PREFIX" "$STAGE/usr"
cp "$SCRIPT_DIR/launcher/run-dsh.sh" "$STAGE/usr/bin/run-dsh"
chmod +x "$STAGE/usr/bin/run-dsh"
# 打包 usr 的内容（条目为 bin/ lib/ 等，不含 usr/ 前缀），
# 应用解压到 prefix（filesDir/usr）后即得正确的 usr/bin/node
(cd "$STAGE/usr" && zip -r -q "$OUT/runtime.zip" .)
ls -lh "$OUT/runtime.zip"

cat > "$OUT/metadata.json" <<EOF
{
  "arch": "$ARCH",
  "termuxApp": "$TERMUX_APP_VER",
  "dsh": "$DSH_VERSION",
  "nodeVersion": "$NODE_VER",
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "sizeBytes": $(stat -c %s "$OUT/runtime.zip")
}
EOF
echo "==> 完成: $OUT/runtime.zip"

echo "==> [7/7] 收集原生可执行与动态库（jniLibs）"
NATIVE="$WORK/native-libs"
rm -rf "$NATIVE"
mkdir -p "$NATIVE"
# AGP 只打包 jniLibs 中的 .so 文件，可执行文件需以 .so 结尾命名；
# 应用会在 filesDir/bin 下建符号链接（bash->libbash.so）供 exec。
cp "$STAGE/usr/bin/node" "$NATIVE/libnode.so"
cp "$STAGE/usr/bin/bash" "$NATIVE/libbash.so"
cp "$STAGE/usr/bin/bash" "$NATIVE/libsh.so"
cp "$STAGE/usr/bin/rg" "$NATIVE/librg.so"
for b in "$NATIVE"/*; do chmod +x "$b"; done
echo "    可执行文件: $(ls "$NATIVE" | tr '\n' ' ')"
python3 "$SCRIPT_DIR/collect_libs.py" "$STAGE/usr" "$NATIVE" node bash rg sh
tar -czf "$OUT/native-libs.tar.gz" -C "$NATIVE" .
ls -lh "$OUT/native-libs.tar.gz"
