#!/system/bin/sh
# DSHM GUI Computer Use CLI（GuiManager 从本资源复制到 prefix/bin/gui）。
# token 鉴权仅本机 127.0.0.1；__DSH_HOME__ 由生成时替换为 dshHome 绝对路径。
# 在 rootfs 会话内 dshHome 挂载于 /root/dsh，截图路径按运行环境拼接。
CFG='__DSH_HOME__/.gui-config'
PORT=$(sed -n 's/.*"port":\([0-9][0-9]*\).*/\1/p' "$CFG" | head -1)
TOKEN=$(sed -n 's/.*"token":"\([^"]*\)".*/\1/p' "$CFG" | head -1)
if [ -d /root/dsh ]; then GUI_HOME=/root/dsh; else GUI_HOME='__DSH_HOME__'; fi
if [ $# -eq 0 ]; then
  echo "usage: gui dump [max=N] | state | tap x y | longpress x y | swipe x1 y1 x2 y2 [ms] | text '单行文本' | key back|home|recents | shot"
  exit 0
fi
cmd="$1"; shift
req() { curl -s --max-time 30 -H "X-Gui-Token: $TOKEN" "$@"; }
reqpost() { curl -s --max-time 30 -X POST -H "X-Gui-Token: $TOKEN" -H 'Content-Type: application/json' -d "$1" "$2"; }
esc() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
case "$cmd" in
  dump) req "http://127.0.0.1:$PORT/dump?$*" ;;
  state) req "http://127.0.0.1:$PORT/state" ;;
  tap) reqpost "{\"x\":$1,\"y\":$2}" "http://127.0.0.1:$PORT/tap" ;;
  longpress) reqpost "{\"x\":$1,\"y\":$2}" "http://127.0.0.1:$PORT/longpress" ;;
  tapref) reqpost "{\"ref\":$1}" "http://127.0.0.1:$PORT/tap" ;;
  longpressref) reqpost "{\"ref\":$1}" "http://127.0.0.1:$PORT/longpress" ;;
  swipe) reqpost "{\"x1\":$1,\"y1\":$2,\"x2\":$3,\"y2\":$4,\"ms\":${5:-300}}" "http://127.0.0.1:$PORT/swipe" ;;
  text) reqpost "{\"value\":\"$(esc "$1")\"}" "http://127.0.0.1:$PORT/text" ;;
  key) reqpost "{\"action\":\"$1\"}" "http://127.0.0.1:$PORT/key" ;;
  vstatus) req "http://127.0.0.1:$PORT/vdisplay/status" ;;
  vcreate) reqpost "{}" "http://127.0.0.1:$PORT/vdisplay/create" ;;
  vdestroy) reqpost "{}" "http://127.0.0.1:$PORT/vdisplay/destroy" ;;
  vlaunch) reqpost "{\"component\":\"$1\",\"display\":${2:-0}}" "http://127.0.0.1:$PORT/vlaunch" ;;
  vtap) reqpost "{\"display\":$1,\"op\":\"tap\",\"x\":$2,\"y\":$3}" "http://127.0.0.1:$PORT/vinput" ;;
  vswipe) reqpost "{\"display\":$1,\"op\":\"swipe\",\"x1\":$2,\"y1\":$3,\"x2\":$4,\"y2\":$5,\"ms\":${6:-300}}" "http://127.0.0.1:$PORT/vinput" ;;
  vtext) reqpost "{\"display\":$1,\"op\":\"text\",\"value\":\"$(esc "$2")\"}" "http://127.0.0.1:$PORT/vinput" ;;
  vkey) reqpost "{\"display\":$1,\"op\":\"key\",\"action\":\"$2\"}" "http://127.0.0.1:$PORT/vinput" ;;
  vshot) req "http://127.0.0.1:$PORT/vscreenshot?display=$1" | sed "s|\"path\": *\"|\"path\":\"$GUI_HOME/|" ;;
  shot) R=$(req "http://127.0.0.1:$PORT/screenshot")
        echo "$R" | sed "s|\"path\": *\"|\"path\":\"$GUI_HOME/|" ;;
  *) echo "usage: gui dump [max=N] | state | tap x y | tapref N | longpress x y | longpressref N | swipe x1 y1 x2 y2 [ms] | text '单行文本' | key back|home|recents | shot | vstatus | vcreate | vdestroy | vlaunch <component> [display] | vtap <display> x y | vswipe <display> x1 y1 x2 y2 [ms] | vtext <display> '文本' | vkey <display> back|home|recents | vshot <display>" ;;
esac
