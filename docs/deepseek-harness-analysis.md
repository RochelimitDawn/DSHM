# DeepSeek Harness 代码级分析

> 分析对象：<https://github.com/deepseek-ai/deepseek-harness>（克隆于 `/workspace/deepseek-harness`，commit `47f9438`）
>
> DeepSeek Harness（CLI 命令名 `dsh`）是 DeepSeek AI 开源的 Agent harness，目前处于 developer preview 阶段，官方声明会有破坏性变更。它采用"一切皆插件"（everything is a plugin）的架构，底层基于 vendored 的 [Cordis](https://github.com/cordiverse/cordis) 插件框架。

## 一、项目概况

- 仓库形态
  - 是一个 pnpm monorepo，工作区包含 `vendor/`、`packages/`、`apps/`、`python/`、`native/`、`examples/`、`website/`
  - 全部为 ESM（`"type": "module"`），要求 Node.js `^22.19 || >=24`，包管理器 pnpm 11
  - npm 包统一命名 `@deepseek-ai/dsh-<name>`；`@deepseek-ai/cordis` 是所有 harness 包的 peerDependency
- 目录职责
  - `vendor/`：vendored 的 Cordis 源码副本（固定 SHA 版本，有独立同步流程）
  - `packages/`：约 50 个 `@deepseek-ai/dsh-*` 包，按 `core`、`api`、`llm`、`shell`、`subprocess`、`terminal`、`fs`、`lsp`、`web`、`subagent`、`sandbox`、`session`、`sdk`、`boot` 等分组
  - `apps/`：`cli`（命令行入口）和 `web`（浏览器应用）
  - `python/`：Python SDK（`deepseek-harness-sdk`）与打包的运行时二进制
  - `native/`：Landlock 沙箱原生模块（`landlock-run`，约 300 行 C11）
  - `examples/`：可运行的 cordis.yml 示例（headless-agent、acp-agent、jsonrpc-agent 等）
  - `docs/`：架构、子系统、用户指南、事后分析（postmortem）等文档
  - `website/`：VitePress 文档站
- 产品定位
  - 一个可自托管的编码/通用 Agent 运行时，类似 Claude Code / Codex 的开源实现
  - 提供 Web UI、CLI、headless 一次性任务、Agent Client Protocol（ACP）自动化服务器、TypeScript/Python SDK 等多种接入形态
  - 默认绑定 DeepSeek 模型（`deepseek-official` provider），同时可配置 pi-ai 多 provider 目录（Anthropic/OpenAI/Bedrock/Vertex/Azure/Codex 等），并能通过子代理桥接真实的 Claude Code / Codex

## 二、架构设计

### 1. 插件系统：一切皆插件

- Cordis 五大概念（`docs/cordis-primer.md`）
  - 插件是实现 Service 的对象：可以是带 `inject`/`apply(ctx)` 的函数，也可以是 `Service` 子类
  - 上下文是服务仓库：服务占据稳定的 `ctx.<key>`（如 `ctx.tools`、`ctx.llm`、`ctx.sessions`），其他插件通过 key 发现服务而非 import 具体实现
  - 依赖用 `inject` 声明：插件等待所需服务就绪后加载，用服务需求表达加载顺序
  - 类型化事件通信：事件通过 TypeScript 声明合并（`declare module`）定义，按 `emit` / `waterfall` / `parallel` / `serial` 四种模式分发
  - 注册是可逆 effect：工具、提示段、适配器、provider、监听器都经 `ctx.effect()` / `ctx.on()` 安装，卸载时按生成器 yield 顺序逆序回滚
- 事件分发模式（`vendor/cordis/src/events.ts`）
  - `emit`：同步、不等待、无返回值，按注册顺序观察
  - `waterfall`：around-middleware，监听器收到 `(…args, next)`，调用 `next()` 委托（可包装结果），不调用则短路；用于 `agent/pre-step`、`agent/request`、`tools/pre-execute` 等策略点
  - `parallel`：并行等待全部监听器
  - `serial`：按注册顺序串行，直到 bail
  - 分发模式是事件的公开契约，用 `@mode` 标注，生成目录会校验声明与分发点一致
- 作用域（scope，`packages/core/scope/src/index.ts`）
  - 每个 agent 有独立的 scope，注册按 scope key 隔离：scoped 工具/提示/变量可 shadow（覆盖）同名全局项
  - `agent.ctx` 同时承载"scope 可见"与"scope 生命周期"两个事实
  - 事件沿 scope 链向上传播（祖先能看到子孙事件），支撑 preset 继承与 agent 隔离

### 2. Profile / Bundle 组合机制

- 一个运行的 `dsh` 是启动时按有序层次组合出的插件树
  - profile：`$DSH_HOME/profiles/<name>/` 下的命名组合，声明其堆叠的 bundle 列表和用户的 `cordis.patch.yml`
  - bundle：npm 发行格式的 Cordis 配置行 + 代码（`package.json` 的 `dsh.bundle.patch` 指向其 `cordis.patch.yml`）
- 层次顺序（`packages/boot/app-boot/src/profile.ts`）
  - 空 entry 列表 → 每个 bundle 的 patch（按 profile 声明的 bundles 顺序）→ profile 的 `cordis.patch.yml` → home 级 `$DSH_HOME/cordis.patch.yml` → `--patch` 覆盖层
  - patch 按行 id 定位并整行替换 config（不做深合并），也可插入新行
  - 用 `dsh --profile web --dump-config` 可查看实际启动的完整配置树
- 内建 bundle
  - `dsh-base`：每个 profile 的第一层（agent、LLM、工具、持久化、权限与沙箱策略、settings/credentials、遥测、宿主级子代理 provider）
  - `dsh-web-app`：浏览器表面（webserver、API gateway、workspace、client 插件名单）
  - `dsh-headless`：一次性任务模式，无 Host/HTTP/Web
- 会话级组合：agent preset
  - `agent-presets` 把 agent 的 scope key 绑定到 standing 组合的 scope parent，使 preset 的注册与监听器覆盖该 agent
  - preset id 持久化进 `SessionHeader.agentPreset`，保证 resume 不改变会话的工具与提示组合
  - 内建四档 preset：标准（完整编码 Agent）、极简（仅持久 bash + str_replace_editor）、PTC（Code Mode）、创造（cordis）

### 3. 核心包矩阵

| 包（`packages/core`） | 职责 | `ctx` key |
|---|---|---|
| `session` | 追加式 `SessionEvent` 日志与内存存储 | `ctx.sessions` |
| `system-prompt` | 提示段与工具 schema 组装 | `ctx.systemPrompt` |
| `tools` | 作用域工具注册表与受管执行流水线 | `ctx.tools` |
| `agent` | `Agent` 接口、实时注册表、`agent/*` 事件 | `ctx.agents` |
| `agent-loop` | 实现 `Agent` 接口的默认驱动（ReactLoopAgent） | `ctx.agentLoop` |
| `scope` | 每 agent 作用域注册原语 | 库，无 key |

- 扩展点总览（改行为优先用插件挂接，改循环需更新架构文档）
  - 加模型 provider：在 `ctx.llm` 注册适配器
  - 加模型可见能力：在 `ctx.tools` 注册，其 schema 自动加入提示组装
  - 加 shell/终端/子代理/作业/文件系统：在对应 seam 注册 provider
  - 拦截请求/工具/turn：用 `agent/*` 或 `tools/*` 事件
  - 加模型可见上下文：`agent.inject()`，落在下一个被接收的请求
  - 加持久会话状态：扩展 `SessionEventMap`，从日志渲染与回放

### 4. 能力接缝（Capability Seam）

- 定义：一个可替换能力包含三个角色，缺一不可（`docs/capability-seams.md`）
  - Service Definition：声明接口的 Cordis `Service`（抽象类或具体注册表），占 `ctx.<key>`
  - Service Provider：实现该接口的后端
  - Consumer：注入并使用该服务的人，通常是模型可见工具
- 典型接缝示例
  - `ctx.llm`：`dsh-llm`（定义）+ `llm-deepseek` / `llm-pi-ai` / `llm-replay`（provider）+ `agent-loop` / compaction（consumer）
  - `ctx.shell`：`dsh-shell`（定义）+ `bash-local` / `bash-sandbox` / `pwsh-local`（provider）+ `tool-bash` / `tool-pwsh`（consumer）
  - `ctx.fs`：`dsh-fs`（定义）+ `fs-local` / `fs-sandbox` / `fs-e2b`（provider）+ `tool-fs`（consumer）
  - `ctx.sandbox`：`dsh-sandbox`（定义）+ `sandbox-local`（provider）+ `bash-sandbox` / `terminal-bash`（consumer）
  - `ctx.subprocess`：`dsh-subprocess`（定义）+ `subprocess-local` / `subprocess-e2b`（provider）+ bash/terminal/LSP/子代理后端（consumer）
  - `ctx.web`：`dsh-web`（定义）+ `web-search-exa/perplexity/deepseek` / `web-fetch-http`（provider）+ `tool-web`（consumer）
  - `ctx.sessionPersistence`：接口（定义）+ `session-persistence-sqlite` / `-jsonl`（provider）+ 多个 consumer
- 价值：换一个 provider 即改变整个产品行为。例如把 fs/subprocess provider 指向远程沙箱，Bash、PTY、LSP 一起迁移

## 三、核心运行机制

### 1. 会话日志（Session Log）：事件源模型

- `Session` 是追加式日志（append-only log，`packages/core/session/src/index.ts:425`），是"事件的源"，同时维护内存存储与派生出的 LLM 消息历史
- 事件不可变：接纳时对事件与嵌套 data 做深冻结（deepFreeze），`events` getter 返回缓存的不变快照
- 事件包络：`type / seq / time / data` + 条件字段 `surfaceOp`、`sourceEventSeqs`、`ignorable`
  - `seq = log.length` 连续性契约保证持久化可原样存储；seed 校验要求 seq 从 0 连续
  - `ignorable: true` 标记纯信息记录，读取方遇到不认识且未标记的类型必须拒绝重建
- 事件词汇表 `SessionEventMap`（可声明合并扩展）：`turn/start`、`turn/end`、`step/start`、`step/end`、`user/message`、`assistant/chunk`（token 级）、`assistant/message`（带 usage）、`tool/call`、`tool/result`、`todo/write`、`request/header`、`request/context` 等
- Header 与日志分离：`SessionHeader` 存 format version、cwd、parentSession（fork 谱系）、seedLength、delegationDepth、agentPreset，属存储元数据，不进事件日志
- 模型上下文派生：Surface 机制
  - 只有 `user/message`、`assistant/message`、`tool/result` 三类事件进入模型可见 surface，且必须携带 `surfaceOp` 标记
  - `SurfaceOp` 为 `'append'`（追加尾部）或 `{ op: 'replace', start, end }`（位置替换，compaction 使用）；替换事件必须在 `sourceEventSeqs` 中列出所有被遮蔽节点
  - `Session.deriveMessages()` 从 surface nodes 逐节点投影，按新节点增量扩展缓存，replace 后重建
- 关键不变量（AGENTS.md）：**Model-visible ⟺ logged** —— 任何到达模型请求的内容必须能从会话日志重建；新增模型可见输入必须新增 session 事件

### 2. Turn / Step 驱动流程

- 术语
  - step：一次模型请求加上它调用的工具执行
  - turn：零或多个 step，开启于第一个输入被认领前，关闭于"无所亏欠"时
- 驱动时序（`packages/core/agent-loop/src/agent.ts`，ReactLoopAgent）
  - `turn/start` → `preStep()`：`inbox.claim()` 认领输入 → `systemPrompt.assemble()` 组装提示段与工具 schema → `agent/pre-step` waterfall 决策（enter 或 reject）；reject 时 turn 以 blocked 结束且不消耗 step
  - `step/start` → 逐条 append `user/message`（surfaceOp: append）→ `step()`：
    - `buildRequest()`：从 `session.requestHeader()` 恢复持久化配置 → `agent/request` waterfall → `llm.prepareCall()` 解析 adapter → 需要时 append `request/header` / `request/context`
    - `llm.stream()` 流式请求，逐 chunk append `assistant/chunk` 并喂给块组装器；流终态 error/aborted 走 `agent/request-error` waterfall 决定 retry
    - 组装完成 append `assistant/message`（`sourceEventSeqs` 引用其 chunk）
    - 提取 tool-call 块交给 `executeToolCalls()`
  - `step/end` → 若工具仍需请求或下一 step 输入到达，再 claim → 下一 step；否则 `agent/turn-stopping`（serial，无 next()）→ `turn/end`
- Inbox：`next-turn` 与 `next-step` 两个队列，所有变更经 `agent/inbox/spliced` 事件持久化到日志；部分消息立即唤醒驱动，注入的上下文在 inbox 等待直到其他消息到达
- 工具调度（`packages/core/agent-loop/src/tool-calls.ts`）
  - 按模型顺序规划：`exclusive` 工具形成 barrier，`parallel` 工具进入有界滚动池（`maxParallelToolCalls` 上限）
  - 每个调用先 append `tool/call`，结果按模型顺序提交时 append `tool/result`（`sourceEventSeqs: [callSeq]`）
  - abort 时为未启动调用补写合成错误 `tool/result`，保持回放有效

### 3. 工具执行流水线

- `ToolDefinition`（`packages/core/tools/src/index.ts:222`）：schema + `execute()` + 强制 `output{schema, render, presentationMeta?}` + 可选 `timeoutMs`、`isConcurrencySafe`、`finalizeContent`、`presentCall/presentResult`
- 注册与治理：`register()` 校验后经 ScopedLayers effect 注册；`restrict()` 做 agent 级 allow/deny 过滤；`guard()` 注册单调守卫（任何守卫可否决，无守卫能强制放行）
- 执行流水线（对应 `docs/tool-execution-pipeline.md`）
  1. `execute()` → 参数无损失快照、Code Mode collapse 检查
  2. `tools/pre-execute` waterfall → allow / deny / ask
  3. ask 经 `ctx.approval` seam 一次性审批：无审批渠道时 fail-closed 拒绝（unavailable）
  4. 单调守卫 `guardReason()`
  5. `tools/execute` waterfall 包裹 `dispatchToolBody()`
  6. `tools/post-execute` waterfall：accept / block / replace，可附加 additionalContexts
  7. 结果规范化 → `tools/result` 同步通知冻结的权威结果
- 超时：`timeoutMs` 由 `dsh-tool-call-timeout-policy`（一个 `tools/execute` wrapper）执行，从不发给模型
- 沙箱：`ctx.sandbox` 是进程约束 seam，`SandboxMode` 三档：`read-only` / `workspace-write` / `danger-full-access`；`sandbox-local` 提供 Linux bwrap/Landlock、macOS Seatbelt、Windows 等后端

### 4. LLM 适配层

- `LlmRuntime`（`packages/llm/llm/src/index.ts`）
  - `registerAdapter(providers, adapter)`：provider 路由 → 适配器，全部或全不，返回可 `replace` 的 handle（支持 HMR 原位换路由）
  - `prepareCall()` 解析模型信息（补默认 maxTokens、校验 reasoningEffort），返回一次性 `PreparedLlmCall`，保证 header 记录与 dispatch 使用同一适配器注册
  - `stream()` 经 `llm/stream` waterfall（中间件可包裹）到 `adapterStream()` 边界，迭代失败全部收敛为 terminal error/aborted finish chunk
- `LlmAdapter` 抽象：`providerInfo / providerRetryPolicy / listModels / resolveModel / stream`
- 支持的 provider
  - `deepseek-official`（`llm-deepseek`）：fetch + SSE 打 DeepSeek（OpenAI 兼容）chat-completions 端点，支持 thinking mode / `reasoning_effort`（off/high/max）；默认模型 `deepseek-v4-flash` / `deepseek-v4-pro`；bearer token 走 `ctx.credentials` seam 按请求解析，缺 key 抛 `MISSING_CREDENTIAL`
  - `llm-pi-ai`：基于 pi-ai 的多 provider 适配器，支持 deepseek / anthropic / openai / bedrock / vertex / azure / codex 以及任意 OpenAI 兼容网关；默认 dormant 挂载，由用户 settings 激活
  - `llm-replay`：测试用重放适配器

### 5. 会话持久化

- 接口：`SessionPersistence` 抽象服务（`packages/session/session-persistence/src/index.ts`）：`create / append / load / inspect / readFrom / list / listSnapshots / prepare`
- SQLite 后端（默认，`session-persistence-sqlite`）
  - 三张表：`persistence_state`（store_id）、`sessions`（每会话一行 SessionHeader 元数据 + incarnation + 单调 revision）、`events`（每个 SessionEvent 一行 1:1，`data` 存 JSON 文本，`source_event_seqs` / `surface_op` 编码列）
  - `SCHEMA_VERSION = 15`，application_id `0x44534850` 保护，版本不符拒绝而非迁移
  - lazy materialization：`sessions` 行只在第一次 append 时写入
  - `appendBatch()` 单事务写入；torn-tail 语义容忍最后一个 `turn/end` 之后的数据损坏
  - 写路径：`session/event` 订阅 → `PersistenceCoordinator` → `SessionWriteBehind` 每会话有界批处理（定时后台写 + 显式 flush 屏障）
- JSONL 后端：每个会话一个 .jsonl 文件（支持 zstd 编码），metadata 在首行
- 落盘触发：`session/flush` 是 awaited parallel durability checkpoint，由 checkpoint policy 按请求调用；teardown drain 时也 flush

## 四、产品特性

### 1. Web UI

- 默认服务在 `http://127.0.0.1:3080`
- 使用流程（`docs/user/guide/index.md`）
  - Settings → Models 输入 DeepSeek API key，保存后立即可用，无需重启；密钥 write-only，存于 `$DSH_HOME/.credentials.yaml`，永不进入 `process.env`
  - Choose workspace 添加项目目录（未选择工作区前 composer 不可用）
  - 发送任务后 agent 可读写工作区文件、运行命令、委派子代理、维护计划；需要审批的操作经权限策略弹窗询问
- 前端由 `@deepseek-ai/dsh-client-*` 系列插件构成（浏览器 shell + React 渲染桥 + 浏览器-宿主 RPC connection）
  - `ui-conversation`：对话主界面（流式尾部、turn 状态、todo 计划条、fork/分支、图片粘贴、审批接管 composer、键盘提交）
  - `ui-subagent`：子代理导航与行内引用
  - `ui-trajectory`：agent 活动轨迹视图
  - `ui-settings` 系列：通用 / 模型 provider 配置 / 插件管理 / 插件清单
  - `ui-goal` / `ui-plan` / `ui-jobs` / `ui-model-selection` / `ui-permission-presets` 等
- 会话持久化默认 headless/web 用 JSONL，也可配置 SQLite

### 2. CLI 与命令行参数

| 命令 | 用途 |
|---|---|
| `dsh --profile <name>` | 启动 `$DSH_HOME/profiles/<name>` 下的命名 profile |
| `dsh web` | `--profile web` 的别名 |
| `dsh --profile headless "job"` | 运行一个全新持久会话，打印最终答案后退出（completed 退出 0，否则 1） |
| `dsh plugin --profile <name> <pnpm args>` | 通过转发 pnpm 管理 profile 的插件 |
| `dsh --profile web --dump-config` | 打印组合后的配置树而不启动 |

- launcher 参数：`--profile`、`--patch`（可重复）、`--dump-config` / `--dump-default-config`、`-V/--version`、`--help`；第一个无法识别的 token 之后的参数原样交给被启动的应用（如 `--port 8080` 属于 web app）
- 关键环境变量：`DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`DSH_HOME`（默认 `~/.dsh`）、`DSH_PERMISSION_MODE`（默认 workspace-write）、`DSH_TOOLS_MODE`（native/code/both）、`DSH_TELEMETRY_MODE`（默认关闭）、`DSH_MODEL`、`DSH_SYSTEM_PROMPT`、`DSH_SESSION_ROOT`

### 3. 内建工具清单

`docs/tool-catalog.md` 收录 52 个 tool schema，默认随 `dsh-base` 装载的核心工具包括：

| 工具（按包归类） | 功能 |
|---|---|
| `bash` / `pwsh` / 持久 `bash` | 一次性 / Windows / 持久 PTY shell 执行 |
| `read` / `write` / `edit` / `read_image` | 文件系统读写编辑 |
| `glob` / `grep` | 路径模式与 ripgrep 内容搜索 |
| `terminal_open/list/read/send/signal/close` | 持久终端会话管理 |
| `str_replace_editor` | view / create / str_replace / insert |
| `web_search` / `web_fetch` | 网络搜索与抓取 |
| `subagent` / `subagent_fork` | 子代理委派（continuable / one-shot） |
| `interrupt_agent` / `list_agents` / `send_message` | 子代理控制 |
| `report` | 子代理向父代理汇报 |
| `workflow` / `ralph` | 动态工作流 / 固定 fresh-agent 循环 |
| `todo_write` | 会话任务清单 |
| `create_goal` / `get_goal` / `update_goal` | 持久目标管理 |
| `job_list` / `job_output` / `job_kill` | 后台作业管理 |
| `lsp` | goToDefinition / findReferences / goToImplementation / hover |
| `skill` | 加载技能 |
| `ask_user_question` | 暂停向用户提问 |
| `exit_plan_mode` | 退出计划模式 |
| `session_event_read/search/trace`、`session_search/trace` | 会话日志查询 |
| `run_code` | Code Mode：执行 TypeScript 程序 |
| `schedule_create/delete/list` | 本地持久提醒 |

### 4. 能力包全景

- shell / subprocess / terminal
  - `dsh-shell`：执行器契约；`bash-local`/`bash-sandbox`/`pwsh-local`/`pwsh-sandbox` 后端
  - `dsh-subprocess`：`ctx.subprocess` 服务（可执行查找、受管子进程树、PTY 原语），`subprocess-local` 用 node-pty 实现；是 bash、LSP 宿主、PTY shell、ACP 子代理的公共进程基座
  - `dsh-terminal`：按 owner 隔离的持久 PTY 会话，跨 tool 调用保持 shell 状态
- fs / lsp / web
  - `dsh-fs`：路径 / 文本 IO / 原子变更；`fs-sandbox` 按权限模式围栏写操作；`fs-observation-policy` 实现 read-before-edit + 版本守卫策略
  - `dsh-lsp`：`lsp-stdio` 通用多服务器 stdio 后端（经 ctx.fs + ctx.subprocess）
  - `dsh-web`：search/fetch provider 注册表，支持 Exa / Perplexity / 原生 DeepSeek 搜索；凭据携带的 provider 请求拒绝重定向
- subagent
  - `subagent-spawn-in-process` / `subagent-fork-in-process`：进程内子代理
  - `subagent-acp`：进程外 ACP 兼容运行时
  - `subagent-codex`：真实 Codex `app-server --stdio` 子代理（默认 disabled）
  - `subagent-claude-code`：官方 Claude Agent SDK 启动宿主 `claude` CLI（默认 disabled）
  - `subagent-dsh-sdk`：进程外 Harness 运行时
- todo / plan / goal
  - `todo_write` 存储会话任务清单，UI 渲染为计划条
  - plan-mode：计划模式状态 / 指导 / 命令 / 审查流程
  - goal：持久同会话目标，带 revisioned 阶段（active/paused/blocked/complete）与 goal-round 上限；`/goal` 人类命令
- jobs / sandbox / code-runtime
  - `dsh-jobs`：后台任务注册表与生命周期，`job_*` 工具收集或停止
  - `dsh-sandbox` + `sandbox-local`：三档进程约束模式；`native/landlock-run` 是 Linux Landlock 静态链接启动器（fail-closed）
  - `dsh-code-runtime`：Code Mode 在 worker 线程执行模型写的 TypeScript 程序
  - `dsh-e2b`：实验性 POC，把 fs/subprocess 执行世界放进 E2B Linux 沙箱
- 支撑能力
  - settings（分层用户设置，热重载）/ credentials（凭据引用 provider）/ interaction（审批、权限 preset、斜杠命令、ask-user）/ hooks（Claude Code / Codex 外部 shell hook 桥接）/ guard（超时、重复提醒）/ compaction（上下文压缩）/ spill（大输出存储）

### 5. Agent Client Protocol 与 SDK

- ACP（`packages/acp/acp`）：纯自动化 JSON-RPC stdio 服务器，让程序化客户端（父代理、子代理 provider、CI）驱动 harness agent
  - 方法：`initialize`、`session/new`、`session/prompt`、`session/cancel`、`session/update`、`session/request_permission`
  - 限制：仅全新会话、仅 baseline 提示与单工作区、仅已提交答案、连接级生命周期
  - 运行示例：`pnpm run demo:acp`
- JSON-RPC SDK（`packages/sdk`）
  - `protocol`：每行一个 JSON-RPC 2.0 帧的线协议；方法 `initialize`、`session/prompt`（返回 `{messageId}` 回执）、`shutdown`；通知 `session.event`（全量会话日志）、`session.status`、`subagent.started/finished`
  - `client`：高层 `DeepSeekHarness`（`harness.run()` 返回 `RunResult`）+ 低层 `HarnessClient`
  - `server`：`jsonrpc` 插件在 stdio 上服务 SDK 客户端；`serverInfo.name` 为 wire 稳定的 `deepseek-harness-sdk-runtime`
- Python SDK（`python/`）
  - `deepseek-harness-sdk` + `deepseek-harness-runtime-bin`（打包的运行时二进制）
  - `DeepSeekHarness(provider, model, max_tokens, cwd, session_root, cordis)` 上下文管理器，懒启动运行时并跨调用复用
  - `Session.run()` 返回 `RunResult(session_id, final_response, finish_reason, events, notifications, session_root)`
  - 复用同一 session id 可保留会话拥有的持久 Bash 进程（cwd、导出变量、shell 函数）

### 6. 配置方式

- 三层配置模型
  - cordis.yml / patch 层：组合插件树（bundle → profile patch → home patch → --patch）
  - `$DSH_HOME/settings.yaml`（热重载）：`llm-deepseek:` / `llm-pi-ai:` 段覆盖适配器配置
  - `.env`：项目目录与 `$DSH_HOME/.env` 为启动环境层
- 会话权限默认 `workspace-write`；遥测默认关闭需显式开启；MCP client 随附但默认不启用任何 MCP server

### 7. 示例项目

| 示例 | 演示内容 |
|---|---|
| `headless-agent` | 无头一次性编码 agent：DeepSeek V4 + 本地 bash/fs + 子代理 + workflow + Ralph + todo_write + JSONL；附 e2b 沙箱覆盖层与 Code Mode 高级覆盖层 |
| `acp-agent` | ACP 自动化服务器：每 session/new 一个新 agent、stdout 协议纯净、一次性审批 |
| `jsonrpc-agent` | Python SDK + JSON-RPC 驱动的无人值守编码 agent（minimal.py） |
| `mcp-memory` | 连接第三方记忆服务器（engram / mcp-reference-memory / memorix）的可选覆盖层 |
| `web-cordis` | 自引用 agent：检查并修改自己内存中的 Cordis 插件树 |
| `web-schedule` | opt-in Web 覆盖层：Session 本地持久提醒 |

## 五、关键设计不变量

1. Model-visible ⟺ logged：模型可见输入必有 session 事件，模型上下文从日志 surface 派生
2. Registrations are effects：所有贡献走 `ctx.effect()` / `ctx.on()`，返回精确 disposer，保证 HMR / teardown 可预测回滚
3. 插件而非改循环：新行为走文档化扩展点；改 `agent-loop` 必须更新架构文档
4. 能力接缝三角色完整：Service Definition / Provider / Consumer 缺一不可
5. fail-loud：持久化后端拒绝旧格式（SCHEMA_VERSION、SESSION_FORMAT_VERSION 单调版本，无兼容承诺）；启动期审计失败即退出
6. 作用域链：注册视图向下继承，事件准入向上传播，是 agent 隔离与 preset 继承的同一机制
7. 显式优于隐式：跨包边界用显式 `resolve(request): Spec`，不用隐藏的 `?? default`
8. 无硬编码可调参数：部署期变化的选择都是可验证的 `Config` 字段，可从 cordis.yml 修改

## 六、启动与运行

### 1. 从 npm 运行

```sh
npx @deepseek-ai/dsh web
```

启动 Web UI，默认 `http://127.0.0.1:3080`。

### 2. 从源码运行

```sh
git clone https://github.com/deepseek-ai/deepseek-harness.git
cd deepseek-harness
pnpm install
pnpm run build
pnpm dsh web
```

### 3. 主要脚本

- `pnpm run test`：vitest 单元测试
- `pnpm run test:coverage`：CI 覆盖率门槛（每文件 100%）
- `pnpm run test:e2e`：真实 API 测试（无 `DEEPSEEK_API_KEY` 时自跳过）
- `pnpm run typecheck` / `pnpm run lint`：类型与静态检查
- `pnpm run build`：tsc 生成 lib/types + tsdown 打包运行时
- `pnpm run demo:acp` / `pnpm run demo:cordis`：ACP / 自修改演示（需要 key）
- `pnpm run docs:dev`：VitePress 文档站开发模式
