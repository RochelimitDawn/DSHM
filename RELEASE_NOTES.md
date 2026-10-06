# DSHM v2.2.48

修复终端无法打开的问题。

## 修复：终端报 `command "/data/data/com.termux/files/usr/bin/bash" is not an executable file`

- **现象**：打开 dsh 终端即报 `subprocess-local: command "/data/data/com.termux/files/usr/bin/bash" is not an executable file`，终端不可用。
- **根因**：运行时源自 Termux bootstrap，其环境/profile 把 `SHELL` 设为编译期前缀 `/data/data/com.termux/files/usr/bin/bash`。dsh 的 `subprocess-local` 在 `terminalEnvironment()` 中以 `process.env.SHELL` 作为默认 shell 兜底（`dsh-terminal-bash` 的 `shellPath` 只是显式覆盖），该路径在本应用（包名 `com.siliconleap.app`）下不存在，exec 直接失败。
- **修复**：dsh 服务进程环境显式设置 `SHELL` 指向本应用可执行的 bash（与 `DSH_BASH_PATH` 同源，位于 nativeLibraryDir，SELinux 放行），彻底消除 Termux 前缀残留；子系统会话内 `SHELL` 指向 guest 自身的 `/bin/bash`。

# DSHM v2.2.47

修复插件市场安装失败，并新增工作区缓存加速。

## 修复：插件市场 `ERR_PNPM_UNEXPECTED_STORE`

- **现象**：从插件市场安装需要联网拉取 tarball 的插件（如 dsh-claude-style、dsh-better-sidebar）时，pnpm 以 `ERR_PNPM_UNEXPECTED_STORE: Unexpected store location` exit=1。
- **根因**：pnpm 在 `node_modules/.modules.yaml` 记录上次安装的 storeDir，安装前按当前环境重新解析 store 并做路径比较，不一致即拒绝。历史装配曾在子系统（guest，`HOME=/root` → `/root/.local/share/pnpm/store/v10`）视角执行，而市场实际运行在 App 宿主进程（host，`HOME=files/home`），两套绝对路径写进同一个 profile，运行时必对不上。
- **修复（统一到运行时视角）**：全局钉死一个 host 绝对路径 store（与 pnpm 在 host `HOME` 下的默认推导值一致，抗环境变量丢失）——host 经 `npm_config_store_dir` 与 profile `.npmrc` 生效，guest 侧把该物理目录 bind 到同一字符串路径，两侧解析结果逐字节一致。启动装配时自动检测并清理旧的 guest 视角 store 记录（`.modules.yaml`/lock/虚拟 store），按固定 store 重新生成。
- **导入策略**：profile 级 `.npmrc` 增加 `package-import-method=copy`（跨挂载点/FUSE 硬链接不可用，改复制）与 `node-linker=hoisted`（减少对虚拟 store 符号链接的依赖），提升多层路径映射下的稳定性。

## 新增：工作区缓存加速

- **背景**：工作区在公共存储（sdcard）时经 FUSE，元数据 syscall 慢 5 倍以上（实测 249 文件递归 stat：sdcard 2.23s vs rootfs 0.465s），依赖树遍历/索引/插件扫描被显著放大。
- **方案**：自动识别项目根下的依赖与构建缓存目录（`node_modules`、`.pnpm-store`、`__pycache__`、`.venv`、`.gradle`、`.next`、`.turbo`、`target` 等），迁移到应用私有目录（原生文件系统），再以 bind 覆盖回子系统内的原路径——工具与 AI 看到的仍是普通目录，完全透明。源码文件仍留在用户设置的 sdcard 路径，可直接被文件管理器访问。
- **可控**：设置 → 工作区可开关「工作区缓存加速」并「立即优化工作区」；工作区已在应用私有目录时自动跳过。

# DSHM v2.2.46（构建失败，已由 v2.2.47 取代）

修复兼容插件从未真正生效的问题。

- **根因**：装配进程的 cwd 固定在子系统根目录，而 `dsh plugin --profile web add` 把 pnpm 的安装目标原样交给调用目录——插件包装进了子系统根的 node_modules，profile 工作区（`dsh-home/profiles/web`）里没有。dsh 启动时按「向上查 node_modules」解析插件 bundle，全部解析失败（日志表现为 `skipping profile bundle ... cannot resolve profile bundle ...`），插件面板显示已装但实际从未生效。
- **修复**：装配进程改为在 profile 工作区目录内执行，pnpm 包装进正确位置。
- **自愈**：已受影响的设备无需手动操作——装配状态改按「profile 工作区里包是否存在」判定，缺包自动触发重装。

# DSHM v2.2.44

移除 Clash 代理功能。

- **Clash（mihomo）及其全部依赖、逻辑与 UI 下线**：代理引擎（tawcroot/proot 内持久进程）、订阅下载与配置生成、分流模式（规则/全局/直连）、节点选择对话框、设置页代理卡片、环境变量注入（http_proxy 等）全部移除。
- 网络能力保持不变：apt 走 USTC、npm 走 npmmirror、GitHub 下载多镜像回退（GHProxy），国内网络环境无需代理即可装齐环境。
- 说明：已开启过代理的用户升级后代理设置不再生效（功能已移除）；已下载的 mihomo 二进制残留在子系统 rootfs 内（`/opt/mihomo`），卸载重装子系统即可清除，不影响使用。

# DSHM v2.2.43

正式版首个补丁版本，修复两个设备相关启动问题。

- **修复：装有 Termux 的设备上服务启动即死**（`OpenSSL configuration error ... fopen(/data/data/com.termux/.../openssl.cnf): Permission denied`）。libnode.so 为 Termux 构建，编译期 OpenSSL 配置路径硬编码指向 Termux 应用目录；该设备上装有 Termux 时路径存在但不可读（EACCES 直接致命），未装 Termux 时为可容忍的 ENOENT——所以只有装了 Termux 的用户会崩。现通过 `OPENSSL_CONF` 显式指定运行时自身的 openssl.cnf（缺失时自动生成最小配置），彻底绕开 Termux 路径。
- **修复：Android 14 部分机型（如 iQOO Z9X / vivo OriginOS）授予通知权限后打开即崩**（`BadForegroundServiceNotificationException`）。三层加固：通知渠道换新 id（排除旧渠道被用户或系统异常屏蔽的残留状态）、渠道提前到 Application 启动即创建、前台服务通知发布失败时降级重试（最小通知 + 平台内置图标兜底），兜底仍失败则放弃前台身份，保证应用可打开。

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
