# Requirements Document

Feature: linux-subsystem
Date: 2026-08-16

## Introduction

DSHM 目前内嵌 Termux 运行时，工具链相对有限且部分软件包版本偏旧。本需求为应用加载一个 **Debian 子系统**（proot 免 root 运行），用于补充完整、可更新的工具链：DSH agent 的 shell 命令可在子系统内执行（获得 apt、编译器、Python 等完整工具链），用户也可进入子系统交互式终端。DSH 核心服务（node web 服务）保持在 Termux 原生环境运行以保证性能。

## Glossary

- **系统（The system）**：DSHM Android 应用。
- **子系统（Linux Subsystem）**：通过 proot 运行的 Debian rootfs 环境。
- **proot**：免 root 的系统调用拦截器，模拟 root 与文件系统挂载，无需真 root 权限。
- **rootfs**：Debian 最小化根文件系统（aarch64）。
- **运行时（Runtime）**：应用内嵌的 Termux 原生环境（node、dsh 及其依赖）。
- **下载源（Download source）**：应用设置中的 GitHub/GHProxy（CF 优选/AxisNow 三网）/自定义源，子系统下载复用同一机制。

## Requirements

### R1 子系统安装

**User Story:** AS DSHM 用户，I want 一键安装 Debian 子系统，SO THAT 获得完整可更新的工具链。

#### Acceptance Criteria

1. WHEN 用户在环境页触发安装子系统，系统 SHALL 从当前下载源下载 proot 二进制与 Debian rootfs，并在界面展示下载进度与实时速度。
2. WHEN 下载完成后，系统 SHALL 校验 rootfs 完整性；IF 校验失败，系统 SHALL 中止安装、清理部分文件并向用户展示失败原因。
3. WHEN 安装成功，系统 SHALL 在环境页展示子系统已安装状态、版本号与占用空间。
4. WHEN 已安装子系统，系统 SHALL 允许用户在不重新下载的情况下启动与停止子系统。

### R2 免 root 运行

**User Story:** AS DSHM 用户，I want 子系统免 root 运行，SO THAT 无需设备解锁或 SU 权限。

#### Acceptance Criteria

1. 子系统 SHALL 始终通过 proot 在免 root 权限下启动。
2. 子系统运行 SHALL 不依赖设备 root、Magisk 或任何 SU 提权。

### R3 子系统启动与停止

**User Story:** AS DSHM 用户，I want 启动与停止子系统，SO THAT 按需使用工具链并释放资源。

#### Acceptance Criteria

1. WHEN 用户启动子系统，系统 SHALL 以 proot 挂载 rootfs，绑定 /dev、/proc、/sys 及必要的宿主目录映射，并启动登录 shell。
2. WHEN 用户停止子系统，系统 SHALL 终止对应的 proot 进程及其子进程。
3. WHILE 子系统运行中，系统 SHALL 在环境页展示其运行状态。

### R4 DSH agent 工具链集成

**User Story:** AS DSHM 用户，I want DSH agent 使用子系统内完整工具链，SO THAT agent 能执行 apt、编译器等命令。

#### Acceptance Criteria

1. 当子系统已安装，DSH agent 执行的 shell 命令 SHALL 在子系统环境内执行。
2. 子系统内的 apt 包管理 SHALL 正常工作，用户或 agent SHALL 能安装、升级、移除软件包。
3. 当子系统未安装，DSH agent 的 shell 命令 SHALL 回退到 Termux 原生环境执行，行为与当前版本一致。

### R5 用户交互式终端

**User Story:** AS DSHM 用户，I want 进入子系统交互式终端，SO THAT 手动使用工具链。

#### Acceptance Criteria

1. WHEN 用户请求进入子系统终端，系统 SHALL 提供可交互的子系统 shell 会话。
2. 终端会话 SHALL 支持常见的交互操作（命令输入、输出回显、中断）。

### R6 存储管理

**User Story:** AS DSHM 用户，I want 管理子系统占用的存储，SO THAT 控制应用体积。

#### Acceptance Criteria

1. 环境页 SHALL 展示子系统占用的磁盘空间。
2. WHEN 用户卸载子系统，系统 SHALL 删除 rootfs 与关联文件以释放空间，并保留 Termux 运行时与其数据。

### R7 性能

**User Story:** AS DSHM 用户，I want 子系统功能完整的同时核心服务保持高性能。

#### Acceptance Criteria

1. DSH 核心服务（node web 服务）SHALL 保持运行在 Termux 原生环境。
2. 子系统仅承载工具链与命令执行，不承载核心服务。

### R8 错误处理

**User Story:** AS DSHM 用户，I want 子系统相关操作失败时有明确反馈，SO THAT 我能定位与解决。

#### Acceptance Criteria

1. IF 存储空间不足，系统 SHALL 阻止安装并提示可用空间不足。
2. IF proot 或子系统启动失败，系统 SHALL 展示启动日志与失败原因。
3. IF 下载失败，系统 SHALL 提供重试入口。
