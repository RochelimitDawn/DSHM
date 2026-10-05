# Open Vetta 借鉴方案（研究，不落地）

> 研究对象：https://github.com/openvetta/open-vetta （Apache-2.0，桌面 AI Agent，Bun/TS Monorepo + Kotlin + Go）
> 结论先行：Open Vetta 与 DSHM 定位高度相似（本地优先、BYOK、可扩展的 AI Agent 宿主），但它是桌面端（Electron），我们是移动端（Android 壳 + 在线运行时）。它对 DSHM 的价值主要在**机制与设计**（插件信任模型、扩展分层、移动端协议、通知/自动化），而非直接复用代码——技术栈不同（Bun/Electron vs Android/node），直接移植成本高。

## 一、Open Vetta 是什么

- 桌面 AI Agent 工作区：模型、项目文件、本机工具、可复用能力放在同一工作区；本地优先、BYOK（自带 Key，凭据存本地）。
- 仓库结构：`apps/desktop`（Electron 宿主）、`apps/cli-host`（CLI 宿主）、`apps/mobile/client-apple`（SwiftUI 原生 iOS 客户端）、`apps/mobile/client-android`（Kotlin Multiplatform，**冻结在协议 v1**）、`apps/im-gateway`（Go IM 旁路网关）；`packages/ai`、`packages/agent`（Provider 抽象与 Agent Loop）、`packages/plugins`、`packages/themes`。
- 扩展模型分层：**技能**（让 Agent 遵循可复用方法）< **MCP**（标准协议连外部工具）< **插件**（扩展桌面 UI/文件/消息/工具/宿主动作）< **主题**；SDK/RPC/CLI 用于外部嵌入驱动。
- 数据与构建模式：源码检出默认 **lite 构建**（不依赖官方后端、无账号/订阅/托管市场）；官方安装包可选启用 Vetta Serv（构建期选择，lite 构建静默关闭）。
- 插件信任模型：插件运行在渲染进程，定位为"经策展的代码"而非任意代码沙箱；`plugin.json` 声明能力，高权限操作由宿主授权 + 运行时再次检查。
- 文档工程：`docs/adr/` 架构决策记录、数据边界/配置路径/安全策略分文档、`llms.txt`/`llms-full.txt` 供 Agent 拉取。
- 工程守卫：`bun run check:quick`（改动文件与架构边界）、`check`（lint+类型+架构守卫）、`test:changed`（按 diff 跑测试）。

## 二、与 DSHM 的对照

| 维度 | Open Vetta | DSHM | 借鉴空间 |
|---|---|---|---|
| 形态 | 桌面（Electron）+ 移动客户端 | Android 壳（Kotlin + Compose）+ 浏览器 WebUI | 移动端协议可参考 |
| Agent | 自研 packages/agent（TS） | 在线下载 @deepseek-ai/dsh（node） | 可选第二运行时 |
| 扩展 | 技能/MCP/插件/主题 四层 | 仅"插件"一层（dsh 插件） | 分层思路 |
| 插件安全 | 宿主授权 + 运行时复查 | 社区插件在 node 内全权运行 | 信任模型可借鉴 |
| 通知/远程 | IM 网关 + Webhook + 通知 | 无任务完成推送 | Android 通知可借鉴 |
| 更新/分发 | lite/Serv 构建模式 | runtime-latest 单通道 | 构建变体思路 |
| CI 守卫 | 架构边界 check | 字符串/括号自检（CI 真编译兜底） | 边界检查思路 |

## 三、可借鉴点（按价值排序）

### A. 移动端协议 v1（价值最高：解 WebUI 依赖）
- 它的 Android 客户端（KMP）通过**冻结的协议 v1** 驱动远端 Agent 宿主——与我们"浏览器访问 127.0.0.1:3080"的 WebUI 方案互为替代。
- 现状痛点：dsh-web-mobile 插件做移动端适配（翻页器手势/侧栏/触控），体验受 WebUI 限制。
- 可做：研究其协议 v1 的 RPC 合同（会话/权限/进度/文件传输的边界设计），为 DSHM 规划"原生壳直连 runtime"的第二形态（Kotlin 原生 UI 替代 WebUI 插件），WebUI 降级为兼容模式。
- 关键参考点：协议分层（会话 vs 工具 vs 文件）、权限边界的协议表达、断线恢复。

### B. 插件信任与权限模型（价值高：插件页安全）
- 现状：dshmarket 社区插件在 node 运行时内以全权运行（读写字盘、网络、凭据），仅靠"临时端口验证插件树能否加载"兜底。
- 可做（运行时侧为主，壳侧配合）：
  1. 插件 `plugin.json` 声明能力（网络/文件/凭据/消息），运行时按声明授予；
  2. 高权限操作宿主授权 + 运行时复查（两级检查，与我们的观点一致）；
  3. 插件页 UI 展示每个插件的权限声明（待装配卡片加"权限"角标）。
- 落地载体：dsh 运行时是外部 npm 包（@deepseek-ai/dsh），改不动其内核；先在**插件装配层**（dsh-plugin-manager / 我们可维护的 wrapper）做声明校验与拒绝，壳侧插件页做展示。

### C. 扩展模型分层（中价值：扩展入口轻量化）
- 它明确"简单流程不必被做成完整插件"：技能 < MCP < 插件 < 主题。
- 对应到 DSHM：dsh 侧可区分"轻技能"（提示词/工作流包，如 dsh-purge、dsh-infinite-gen-4 这类提示词包本质是技能）与"重插件"（带 UI 与宿主动作，如 dsh-web-mobile、dshmarket）。
- 可做：dsh-plugin-builds 分发布两种形态（技能包不进 node_modules，进技能目录），插件页分区展示，安装校验路径也不同（技能包无需端口验证）。

### D. 任务通知与 IM 旁路（中价值：移动端补强）
- 现状：长任务（编译/批量装配）完成只能看 WebUI 或日志；用户离开浏览器就断感知。
- 可做：
  1. Android 通知：runtime 完成事件（装配完、命令完、会话结束）经壳侧 native channel 推系统通知（壳已有前台服务通知条，加任务事件通知成本低）；
  2. Webhook 旁路（它的 im-gateway 思路）：dsh 事件回调壳侧 HTTP 端点，壳再分发通知/其他 IM——保持壳为唯一对外入口，guest 不直接出网。

### E. 构建模式（lite/Serv）（中低价值：分发变体）
- 它在构建期选择能力（lite 无后端依赖，不静默开启）——与我们"runtime-latest 单通道"相比多了一种分发变体思路。
- 可做（远期）：runtime 构建变体（标准版 / 精简版不带 pnpm/插件系统，~250MB 级），metadata 声明 feature flags，壳按需下载。当前用户规模下优先级低。

### F. 文档工程（低成本高性价比）
- `llms.txt` / `llms-full.txt`：文档站暴露 Agent 友好索引——我们 docs/ 可生成一个 llms.txt 汇总文档清单与要点，方便后续 Agent 会话快速热身。
- `docs/adr/`：重大决策（umarm 文件协议、versionCode 方案、双引擎调度不对称默认）记录决策/备选/后果三段式，替代散落在 RELEASE_NOTES 的叙述。
- 数据边界分文档：DSHM 的网络面（模型 API/GitHub/代理/GHProxy/订阅源）可写一份"安全与数据边界"，与它的 reference 结构对齐。

### G. CI 架构守卫（低成本）
- 它的 `check:quick` 检查"改动文件与架构边界"。我们可加一个 `tools/check-arch.js`：壳（android/app）不得直接 import runtime-builder 内容、jniLibs 脚本型 .so 清单与 CI 注入清单一致等轻约束，接进现有 CI 的 Kotlin 自检步骤旁边。

### H.（可选，重）Open Vetta 作为第二运行时
- 它的 agent loop 是 TS（Node 20+），我们 UML/proot Debian 环境有 node 22——技术上可跑。
- 但它是桌面宿主架构（Electron 渲染层 + 宿主进程），无头化驱动需走它的 SDK/RPC（冻结协议 v1），适配成本高，且与 @deepseek-ai/dsh 的会话/工作区模型不互通。
- 结论：**不建议现在做**。留作"runtime 多后端"架构演进的备选项；若 DSHM 未来抽象 runtime 合同（启动/会话/事件/文件四接口），再评估。

## 四、推荐路线（若立项）

1. **F + G（文档/守卫，1 天级）**：llms.txt、ADR 目录、check-arch.js——零风险，立即提升工程性。
2. **D1（Android 任务通知，1-2 天级）**：runtime 事件经壳侧 native channel 推通知——用户体验收益最直接。
3. **B（插件权限声明，2-3 天级）**：装配层校验 + 插件页展示——插件生态是 DSHM 差异点，安全先补。
4. **C（技能/插件分层，随 B 顺带）**：提示词包改走技能路径。
5. **A（移动端协议研究，先研究后立项）**：通读其 client-android 协议实现与 docs/adr，输出 DSHM 原生壳协议 RFC——最大工程量（Kotlin 原生 UI + RPC 层），单独立项。

## 五、风险与边界

- dsh 运行时为外部包，B/C/D 的运行时侧改动需落在**我们自己维护的 wrapper/装配层**，升级 dsh 版本时需回归。
- 协议 v1 是它的冻结合同，非跨项目标准；借鉴设计而非直接实现互操作。
- IM 网关（D2）涉及对外消息通道，与"壳为唯一出口"的边界设计需谨慎，先只做 Android 本地通知。
