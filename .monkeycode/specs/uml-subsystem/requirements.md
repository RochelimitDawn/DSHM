# UML 子系统引擎（linux-um-arm64 + umnetx）

Feature Name: uml-subsystem
Updated: 2026-10-03

## Description

为 DSHM 子系统引入 UML 引擎：用 linux-um-arm64（ARCH=um SUBARCH=arm64，bionic 构建的普通 aarch64 用户态进程内核）+ umnetx（零特权用户态网络栈）替换 proot 引擎。消除 ptrace 逐次拦截开销（proot syscall ~28µs vs UML seccomp ~2µs），并为 agent 提供真实用户级空间权限（guest 内真 root）。

## EARS Requirements

**引擎选择**
- WHEN 子系统引擎设置为 `uml` 且宿主满足运行条件（liblinux.so 存在于 nativeLibraryDir） THE SYSTEM SHALL 以 UML 引擎启动/执行子系统命令。
- WHEN 引擎设置为 `proot` 或 liblinux.so 缺失 THE SYSTEM SHALL 回退 proot 引擎（行为与现状一致）。
- WHEN 引擎设置为 `auto` THE SYSTEM SHALL 优先 UML，不可用回退 proot。

**UML 生命周期**
- WHEN 用户启动子系统 THE SYSTEM SHALL 先启动 umnetx（监听 socket），再启动 UML 内核（connect 顺序不可反）。
- WHEN UML 内核进程退出或 umnetx 退出 THE SYSTEM SHALL 将状态标记为停止并记录日志。
- WHEN 用户停止子系统 THE SYSTEM SHALL 通过 guest poweroff / 进程终止停止内核与 umnetx，并清理 socket。

**资产**
- WHEN 安装 UML 子系统 THE SYSTEM SHALL 下载 ext4 rootfs 镜像并做 sha256 校验（沿用现有下载/断点/镜像源逻辑）。
- 内核、stub、umnetx、umarm-cmd 由 APK jniLibs 携带（CI 构建时注入），不在线下载执行（noexec 限制）。

**命令通道**
- WHEN DSH shell/terminal 在 UML 引擎下执行命令 THE SYSTEM SHALL 经 hostfs FIFO 命令通道（req/res）在 guest 内真 root 环境执行，输出完整回传。

**网络**
- WHEN UML 引擎运行 THE SYSTEM SHALL 通过 umnetx 提供外网连通（guest 静态配置 10.0.2.15，网关 10.0.2.2，DNS 10.0.2.3，端口转发默认仅绑 127.0.0.1）。

**非目标**
- 不迁移 node dsh 服务进 guest（性能关键路径保持 Termux 原生）。
- 不支持 IPv6 / DHCP / 多 guest（umnetx 限制）。
