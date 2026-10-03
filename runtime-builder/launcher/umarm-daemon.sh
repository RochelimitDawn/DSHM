#!/bin/sh
# umarm 命令守护进程：hostfs 文件协议命令通道（hostfs 对 FIFO 支持不可靠）。
# host 侧（libumarm-cmd.so wrapper）写 req.<id> → 本守护进程轮询读取 →
# /bin/bash -c 执行 → 输出（stdout+stderr）+ RC 标记写 res.<id> → host 轮询读取。
# 用法：umarm-daemon.sh <share 目录>   （build_umrootfs.sh 写入镜像 /umarm-daemon.sh）

DIR="${1:?need share dir}"
mkdir -p "$DIR"

# 代理兜底环境：mihomo 运行时（umarm-init 写入 /tmp/dshm-proxy-on）命令走本地
# mixed 端口分流；未运行时这些变量指向本地端口无监听，curl/git 回退直连。
if [ -f /tmp/dshm-proxy-on ]; then
  export http_proxy=http://127.0.0.1:7890
  export https_proxy=http://127.0.0.1:7890
  export all_proxy=http://127.0.0.1:7890
fi

while :; do
  for f in "$DIR"/req.*; do
    [ -e "$f" ] || break
    id="${f##*/req.}"
    cmd="$(cat "$f" 2>/dev/null)" || cmd=""
    # 读到空请求立即删除，避免重复执行
    rm -f "$f" 2>/dev/null
    [ -n "$cmd" ] || continue
    {
      sh -c "$cmd" 2>&1
      rc=$?
      # 标记必须独立成行：输出末尾无换行时先补一个，否则 RC 标记粘在最后一行
      # 导致 wrapper grep 无法剥离、RC 解析失败
      printf '\n'
      printf '__UMARM_RC__:%s\n' "$rc"
      printf '__UMARM_DONE__\n'
    } > "$DIR/res.$id.tmp" 2>/dev/null
    mv "$DIR/res.$id.tmp" "$DIR/res.$id" 2>/dev/null || true
  done
  sleep 0.05
done
