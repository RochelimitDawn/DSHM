# DSHM v2.1.59

## 新增

- **UML 子系统双引擎**（linux-um-arm64 + umnetx）：默认引擎切换为 UML 真内核（`auto` = UML 优先、不可用回退 proot）——零 ptrace 拦截开销（syscall ~2µs vs proot ~28µs）、guest 内真 root、umnetx 用户态网络栈零特权上网。构建侧 build_uml.sh（NDK bionic 静态内核 + stub）、build_umnetx.sh、build_umrootfs.sh（Docker arm64 层转 ext4）；应用侧 SubsystemManager UML 启停、umarm 命令通道（req/res 文件协议，端到端回环验证）、设置页/引导页引擎选择。CI 从 uml-latest release 注入内核产物，rootfs 经 uml-debian-subsystem release 分发（gzip 压缩 ext4，~150MB 级下载，解压后 800M 预分配）。
- **子系统预装常用工具集**：curl/wget/unzip/tzdata/git/python3/openssh-client/jq/ripgrep/fd-find/bash-completion/dnsutils，apt 源默认国内镜像（Debian TUNA / Ubuntu USTC），构建期 qemu-user-static + chroot 安装。
- **Clash 代理内置**（mihomo，Clash.Meta 核心）：guest 内 TUN 透明分流（CONFIG_TUN=y）——规则智能分流（GEOSITE/GEOIP：国外走节点、国内直连）、节点组 url-test 自动选优 + fallback 切换（下载慢/访问不通时自动切节点）。设置页「代理」卡片：开关、机场订阅 URL（本地保存，不出设备）、分流模式（规则/全局/直连）、订阅更新；订阅经 hostfs 写入 guest，内置最小规则模板合并（无节点订阅自动 DIRECT 兜底），mihomo 随 guest 启动收口，agent 命令预置 http_proxy 兜底。合并脚本三路径回环验证通过。
- **设置页分区重构**（参考 Eta 分区思路）：六区编排（外观/运行/子系统/体验/数据管理/关于），每区带一句说明与专属彩色矢量 logo（调色板/闪电/终端/星芒/数据库/信息徽标，六枚配色造型互异），全部卡片补 KDoc 注释。

## 修复

- CI 构建链：mihomo release 资产名修正（arm64-v8）、chroot /tmp 权限、apt http 镜像源（minbase 无 CA）、mkfs.ext4 populate 权限（sudo chmod + bind mount 卸载）、rootfs 分发改 gzip 压缩（稀疏镜像按原始字节存储，1.5GB 直传被吓跑问题）。
