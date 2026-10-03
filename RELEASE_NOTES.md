# DSHM v2.2.12-beta

## 修复

- **CI 内核 job NDK 安装静默跳过**：sdkmanager 在许可未接受时执行 `--install` 会静默跳过 NDK 包（上一次运行 umnetx 编译因找不到 NDK clang++ 失败）。改为先 `--licenses` 后 `--install`，并对安装结果显式校验（缺 toolchains/llvm 立即报错）。
- **更新匹配错乱（v2.1.43 误报最新）**：`UpdateManager` 改用完整 release 列表接口替代 `releases/latest`——后者排除 prerelease，且在版本 release 不存在时会回落到资产类 release（如 uml-debian-subsystem），导致匹配错乱。现为按渠道过滤（Beta 版可收 prerelease，稳定版只见正式 release）+ 跳过 draft 与无 `app-release.apk` 的资产 release + 取 versionCode 最高者。
- **versionCode 方案统一**：新 scheme `major*10^7 + minor*10^5 + patch*10^3`（稳定版 +5，同版本号稳定版 > 预发布版），gradle 由 versionName 推导、`UpdateManager.parseVersionCode` 同步，单一事实来源。历史 v2.2.11-beta 手写 versionCode 2022011 与旧解析公式（2021100）不一致，导致该批次用户永远收不到 2.2.x 更新；新 scheme 所有后续版本码必然更大，可正常覆盖安装并恢复更新提示。

# DSHM v2.2.11-beta

## 新增

- **混合引擎调度器**（proot + UML 按命令动态调度）：`hybrid` 新默认——短命令/交互/网络下载走 proot（多核、零常驻、冷启动 ~3ms、直连宿主网络栈），编译/构建/安装/压缩打包/文件密集走 UML（真内核、openat ~6x、TUN 分流）；零 fork 的 case 匹配 + 粘性窗口（5 分钟，消乒乓）+ 显式 UML 标记（意图最高）；UML 预启动消除冷启动、空闲 5 分钟自动回收（阈值可调 5/15/30/不回收）、可用内存 < 1.5GB 跳过预启动、onTrimMemory 立即回收。11 项路由矩阵端到端验证。
- **内存优化四件套**：node V8 堆上限 512MB（防无限增长）、UML mem 自适应（可用 < 2GB 时 256M）、onTrimMemory 内存吃紧立即回收、UML 空闲阈值设置项。
- **下载断点续传**：运行时/rootfs/订阅下载经 HTTP Range 从断点继续（.part 残留复用），256KB 大缓冲。
- **tar 解压并发化**：小文件（< 8MB）内存缓冲后并行写入，大文件同步；解压时间 ~30% 缩短。
- **GUI Computer Use 全链移除**：GuiManager/GuiAccessibilityService/VdisplayManager/GuiUserService/Shizuku 依赖与全部 UI（净删 ~1100 行）。

## 修复（边界条件）

- **UML 僵死同步**：看门狗探测进程死/状态活（panic/doze 半开）时修正状态并清理。
- **心跳权威化**：dispatch wrapper 以 `uml.running` 心跳文件（15s 写入，< 90s 新鲜）判断 UML 运行，替代服务启动时的静态标记（运行期回收/僵死自动降级 proot）。
- **优雅关机**：stopUml 先发 poweroff 标记等 10s（避免 ext4 写入中强杀损坏 rootfs），进程终止兜底。
- **磁盘水位防护**：umarm-daemon 可用 < 100MB 拒绝新请求（输出 RC=28），避免写满损坏。
- **订阅 YAML 炸弹防护**：订阅 > 10MB 拒绝。
- **调度器信息输出改 stderr**（GITHUB_OUTPUT 多行格式失败修复）、umnetx NDK clang++ 自动探测。

# DSHM v2.2.10-beta

## 新增

- **UML 内核 CI 构建**（linux-um-arm64 + umnetx）：build-uml-kernel job（sdkmanager NDK + clang 18），产物发布 uml-latest（liblinux.so/libumarm-stub.so/libumnetx.so + kernel-src-hash.txt），CI 按源哈希复用/重编。上游 LLVM 交叉编译约定（LLVM=1 + aarch64 glibc 头 + 源码级 stub lld 补丁）。
- **发行版切换下线，仅 Debian**：proot/UML 统一 Debian bookworm，引导页五步改四步。
- **Beta 通道**：tag 含 beta 的 Release 自动标记 prerelease。

## 变更

- 仅 Debian 镜像包；镜像预留空间可在应用里后期调整。

# DSHM v2.1.59

## 新增

- **UML 子系统双引擎**（linux-um-arm64 + umnetx）：UML 真内核优先、proot 回退；umarm 命令通道（req/res 文件协议，端到端回环验证）；gzip 压缩 ext4 rootfs 分发（~150MB 级）。
- **子系统预装常用工具集**：apt 源默认国内镜像。
- **Clash 代理内置**（mihomo）：guest 内 TUN 透明分流，规则智能分流 + url-test/fallback，设置页配置订阅与分流模式。
- **设置页分区重构**：六区编排 + 专属彩色矢量 logo + 全卡 KDoc。
