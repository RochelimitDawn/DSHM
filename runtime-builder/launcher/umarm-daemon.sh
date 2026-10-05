#!/bin/sh
# umarm 命令守护进程：hostfs 文件协议命令通道（hostfs 对 FIFO 支持不可靠）。
# host 侧（libumarm-cmd.so wrapper）写 req.<id> → 本守护进程轮询读取 →
# /bin/bash -c 执行 → 输出（stdout+stderr）+ RC 标记写 res.<id> → host 轮询读取。
# 另处理：poweroff 标记（host stopUml 优雅关机）、磁盘水位检查（写满防护）。
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
  # 优雅关机：host stopUml 写 poweroff 标记 → poweroff -f（ext4 写入中强杀会损坏）
  if [ -f "$DIR/poweroff" ]; then
    poweroff -f 2>/dev/null || poweroff
    sleep 5
  fi
  # 磁盘水位检查：可用 < 100MB 时拒绝新请求（写满会导致 res 写入损坏）
  AVAIL=$(df -k "$DIR" 2>/dev/null | tail -1 | awk '{print $4}')
  case "$AVAIL" in
    ''|*[!0-9]*) AVAIL=999999999 ;;
  esac
  if [ "$AVAIL" -lt 102400 ]; then
    # 写满：输出错误标记后清理请求文件（避免无效重试堆积）
    for f in "$DIR"/req.*; do
      [ -e "$f" ] || break
      id="${f##*/req.}"
      rm -f "$f" 2>/dev/null
      printf 'guest disk full (avail %s KB)\n__UMARM_RC__:28\n__UMARM_DONE__\n' "$AVAIL" > "$DIR/res.$id" 2>/dev/null
    done
    sleep 5
    continue
  fi
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
  sleep 0.02
done
