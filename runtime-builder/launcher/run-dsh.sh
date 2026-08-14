#!/usr/bin/env bash
# SiliconLeap 运行时启动器（Termux-style prefix 内的 dsh 服务）。
# 环境变量由宿主编排器注入（PREFIX / HOME / DSH_HOME / TMPDIR / LD_LIBRARY_PATH / PATH）。
set -euo pipefail

: "${PREFIX:?PREFIX 未设置}"
: "${DSH_HOME:?DSH_HOME 未设置}"

PORT="${DSH_PORT:-3080}"
NODE="$PREFIX/bin/node"
DSH_ENTRY="$PREFIX/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"

# 用 Termux 的 ripgrep 替代 @vscode/ripgrep
export DSH_RG_PATH="${DSH_RG_PATH:-$PREFIX/bin/rg}"

mkdir -p "$HOME" "$TMPDIR" "$DSH_HOME"

echo "[run-dsh] node=$NODE"
echo "[run-dsh] dsh=$DSH_ENTRY"
echo "[run-dsh] port=$PORT dsh_home=$DSH_HOME"

exec "$NODE" "$DSH_ENTRY" web --port "$PORT"
