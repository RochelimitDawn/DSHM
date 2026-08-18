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

# credentials-local 要求凭证文件仅属主可读（mode 600），否则拒绝读取导致
# API Key 解析失败。Android 解压/外部写入可能带 group/other 权限位，先收敛。
if [ -f "$DSH_HOME/.credentials.yaml" ]; then
  chmod 600 "$DSH_HOME/.credentials.yaml" 2>/dev/null || true
fi

echo "[run-dsh] node=$NODE"
echo "[run-dsh] dsh=$DSH_ENTRY"
echo "[run-dsh] port=$PORT dsh_home=$DSH_HOME"

exec "$NODE" --expose-internals "$DSH_ENTRY" web --port "$PORT"
