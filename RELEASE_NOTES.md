# DSHM v2.2.10-beta

## 新增

- **UML 内核 CI 构建**（linux-um-arm64 + umnetx）：新增 build-uml-kernel job——sdkmanager 安装 NDK，build_uml.sh bionic 静态编译内核 + stub，build_umnetx.sh 交叉编译网络栈，产物发布到 uml-latest release（liblinux.so/libumarm-stub.so/libumnetx.so，CI 自动覆盖上传）。APK jniLibs 注入 UML 内核，应用启动即具备 UML 引擎能力。
- **Beta 版本通道**：v2.2.10-beta 为首个 Beta 通道版本，tag 含 beta 后缀的 Release 自动标记为 prerelease。

## 变更

- **发行版切换下线，仅支持 Debian**：proot 与 UML 两种引擎统一使用 Debian bookworm 镜像包——build_subsystem.sh/build_umrootfs.sh 移除 Ubuntu flavor 分支；CI 移除 ubuntu-subsystem 资产构建与发布；应用侧移除引导页发行版步骤（五步改四步：欢迎 → 引擎 → 插件 → 确认）、环境页发行版选择与对比对话框、AppSettings 发行版配置。镜像预留空间大小可在应用里后期调整。

# DSHM v2.1.59

## 新增

- **UML 子系统双引擎**（linux-um-arm64 + umnetx）：默认引擎切换为 UML 真内核（`auto` = UML 优先、不可用回退 proot）——零 ptrace 拦截开销、guest 内真 root、umnetx 用户态网络栈零特权上网。umarm 命令通道（req/res 文件协议，端到端回环验证）、设置页/引导页引擎选择。
- **子系统预装常用工具集**：curl/wget/git/python3/openssh/jq/ripgrep 等，apt 源默认国内镜像。
- **Clash 代理内置**（mihomo）：guest 内 TUN 透明分流，规则智能分流 + url-test/fallback 自动切换，设置页配置订阅与分流模式。
- **设置页分区重构**：六区编排，每区带说明与专属彩色矢量 logo，全部卡片补 KDoc 注释。
