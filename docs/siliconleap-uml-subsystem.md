# UML 子系统引擎（Phase 2/3 实施记录）

Updated: 2026-10-03

## 内核 CI 构建与发行版收敛（v2.2.10-beta）

- **内核 CI 构建**：新增 build-uml-kernel job——sdkmanager 安装 NDK（27.2.12479018），`build_uml.sh` bionic 静态编译内核 + stub，`build_umnetx.sh` 交叉编译网络栈，产物发布到 `uml-latest` release（liblinux.so/libumarm-stub.so/libumnetx.so）。workflow 的 UML 内核注入步骤随之始终可用。
- **仅 Debian**：Ubuntu flavor 全链移除（build_subsystem.sh/build_umrootfs.sh 分支、CI ubuntu-subsystem 资产、引导页发行版步骤、环境页发行版选择与对比对话框、AppSettings 发行版配置）。proot 与 UML 统一 Debian bookworm 镜像包；镜像预留空间大小可在应用里后期调整。
- **rootfs 分发**：gzip 压缩 ext4（~150MB 级下载，800M 稀疏预分配，解压后经 GZIPInputStream 提取，空间校验按 4x 解压量级）。

## 子系统增强（v2.1.59，spec: proxy-clash-subsystem）

- **预装常用工具集**：curl/wget/unzip/ca-certificates/tzdata + git/python3/openssh-client + jq/ripgrep/fd-find/bash-completion + dnsutils；apt 源默认国内镜像（Debian TUNA / Ubuntu USTC）；构建期 qemu-user-static + chroot 安装（build_umrootfs.sh，`PREINSTALL=0` 可跳过）。
- **Clash 代理内置（mihomo，Clash.Meta 核心）**：guest 内 TUN 透明分流（CONFIG_TUN=y），App 设置页「代理」卡片配置（开关/订阅 URL/分流模式 rule-global-direct/订阅更新）。订阅由 App 下载写入 hostfs `share/clash/`，guest 侧 merge-clash-profile.py 与内置规则模板合并（无 proxies 订阅自动插入 DIRECT 兜底组），mihomo 随 guest 启动收口。agent 命令预置 `http_proxy=127.0.0.1:7890` 兜底。设计细节见 `.monkeycode/specs/proxy-clash-subsystem/`。合并脚本端到端验证：正常订阅/空订阅/损坏订阅三路径回环测试通过。

## 迁移状态

| Phase | 内容 | 状态 |
|---|---|---|
| Phase 1（验证） | UML 产物构建脚本、应用侧 UML 引擎、umarm 命令通道 | 已实施（`.monkeycode/specs/uml-subsystem/`），内核真机装验证门待 CI 产出后执行 |
| Phase 2（默认切换） | 引擎 `auto` 默认 = UML 优先、不可用回退 proot；设置页引擎选择对话框；引导页引擎步骤（自动/proot） | 已实施 |
| Phase 3（清理） | ProRoot 引擎移除（jniLibs 5 件 + TermuxEnv/SubsystemManager/Settings 逻辑，见 `2026-10-03-remove-proroot-keepalive-vscreen/plan.md`）；proot 降级为兼容回退引擎 | 已实施（代码中 ProRoot 已完全移除） |

## 引擎选择语义

- `auto`（默认）：UML 优先（liblinux.so 等四件 jniLibs 产物就绪 + rootfs 镜像安装 + 内核运行中），任一条件缺失回退 proot。
- `uml`：仅 UML；产物缺失时命令通道整体回退 proot（bash 是 agent 唯一执行通道，不能失效）。
- `proot`：行为与历史版本一致。
- 已保存的历史 `proroot` 值读取时静默迁移为 `proot`（AppSettings）。

## 装备清单

- jniLibs（CI 从 `uml-latest` release 注入）：`liblinux.so`、`libumarm-stub.so`、`libumnetx.so`、`libumarm-cmd.so`（wrapper 脚本，仓库内源码）。
- 运行时下载（`uml-debian-subsystem` release）：`umrootfs-aarch64-debian.ext4` + `metadata.json`。
- umnetx 网络参数：guest 10.0.2.15/24（vec0），网关 10.0.2.2，DNS 10.0.2.3，转发默认仅绑 127.0.0.1。

## Phase 2 基准测试（待真机）

upstream README 的基准（Poco F3，seccomp 模式）供参考：syscall 2.0µs / openat 4.5µs / forkexec 1177µs，对照 proot 28µs / 28µs / 516µs。真机复测用 perfbench（native/proot 控制组 + interleaved 中位数方法论）在 DSHM 应用内 UML 下执行。
