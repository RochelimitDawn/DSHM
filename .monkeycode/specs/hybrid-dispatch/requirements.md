# 混合引擎调度器（UML + proot 动态调度）

Feature Name: hybrid-dispatch
Updated: 2026-10-03

## Description

引擎新增 `hybrid`（新默认）：按命令动态调度 proot 与 UML，各取所长——短命令/交互走 proot（多核、零常驻、冷启动 ~3ms），编译/构建/文件密集/需真实 guest root 走 UML（真内核、openat ~6x、TUN 分流）。UML 预启动消除冷启动、空闲 5 分钟自动回收释放内存。

## EARS Requirements

**命令分类（调度依据，按优先级）**
- WHEN 命令需要真实 guest root（apt install/dpkg/chown 等） THE SYSTEM SHALL 路由 UML。
- WHEN 命令命中重载白名单（gcc/make/cmake/cargo/pip install/npm build/tar 解压等） THE SYSTEM SHALL 路由 UML。
- WHEN 命令显式携带 UML 标记 THE SYSTEM SHALL 路由 UML（用户意图最高）。
- WHEN 命令为短查询（无重载特征）且 UML 未运行 THE SYSTEM SHALL 路由 proot。
- WHEN UML 运行中且近期有使用（< 5 分钟） THE SYSTEM SHALL 后续命令继续路由 UML（避免乒乓）。

**生命周期**
- WHEN 服务启动且子系统已安装且引擎含 UML THE SYSTEM SHALL 后台预启动 UML（不阻塞 server）。
- WHEN UML 空闲超过 5 分钟（无命令使用标记） THE SYSTEM SHALL 自动停止并释放内存。
- WHEN 停止子系统或引擎切换 THE SYSTEM SHALL 取消空闲回收并停止 UML。

**数据一致性**
- 两引擎共享同一 Debian rootfs 与 $DSH_HOME/workspace 绑定，调度零数据迁移。

**降级**
- WHEN dispatch wrapper 无法路由（proot 缺失且 UML 未运行） THE SYSTEM SHALL 回退原生 bash（命令通道不能失效）。

**非目标**
- PTY/交互终端固定 proot（FIFO 协议无 PTY 保真）。
- 不做运行中任务迁移（调度在命令发起前决定）。
