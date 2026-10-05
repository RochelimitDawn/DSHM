# DSHM v2.2.42 正式版

Android 上的完整 Debian 子系统 + AI 编程助手：免 root、不依赖 Termux、全程本地运行。

首个正式版，包含全部核心能力。

## 执行引擎

- tawcroot（基于开源 [wmww/tawc](https://github.com/wmww/tawc) 魔改）：seccomp 用户态通知（systrap）拦截路径类 syscall，单进程原地翻译，路径操作较 ptrace 方案快 2.5～7.5 倍
- proot 自动回退兜底，老系统兼容
- 命令执行、终端、插件装配共用同一套沙箱；装配 cwd 固定 rootfs 根，杜绝 getcwd 类连锁失败

## 代理分流

- 内置 mihomo（Clash Meta 内核）：proxy-providers 订阅解析，base64 节点分享链接与 Clash YAML 通吃
- 国内域名与 IP 自动直连（geosite / geoip 规则），可视化节点选择
- 代理仅作用于子系统内流量（apt / npm / git / AI 请求），不影响手机其他应用
- 本地混合端口 7890 + 本机外部控制器（127.0.0.1:9090），订阅、节点切换、连通性自检全部图形化

## AI 与插件

- DeepSeek dsh CLI + Web UI：终端、AI 会话、任务通知（需在应用内配置自己的 DeepSeek API Key）
- AI 可直接读取子系统日志自我排障（`/root/dsh/logs/*` 全量落盘、即时刷新）
- 插件装配链路全面修通：npm tgz 多源下载（npmmirror 优先）、依赖解析修复、npm registry 镜像注入
- 内置插件市场、用量统计等兼容插件

## 网络与国内优化

- apt USTC 镜像回退、npm npmmirror、GitHub 下载多源（ghproxy 等）
- guest DNS 国内直连（223.5.5.5 / 119.29.29.29）
- dpkg force-unsafe-io + docs/man/locale 排除：f2fs + fscrypt 栈上 fsync 瓶颈优化，装包速度大幅提升
- 工具链（node / python3 / git / ripgrep）启动预装，幂等执行

## 稳定性

- 沙箱 runner 降级修复（none 分支可达）、guest PATH 与环境变量注入、终端 PTY 环境修复
- 更新总开关：本次关闭 / 永久关闭
- versionName 单一事实来源，versionCode 自动推导，稳定版恒高于同号预发布版
- 运行时 r6：全部补丁随应用升级携带

## 下载

[Releases](https://github.com/RochelimitDawn/DSHM/releases) 下载 APK（arm64），首次启动自动拉取运行时镜像。
