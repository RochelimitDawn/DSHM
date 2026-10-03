# DSH 对话通知方案（研究，不落地）

> 状态：**Phase 1/2/3 已落地（v2.2.13-beta）**。实现见 `dsh-plugins/dsh-notification/`（插件）与
> `runtime/TaskNotifier.kt`（壳侧）。落地差异：任务通知渠道为「任务事件」+「会话更新」DEFAULT、
> 「对话消息」HIGH；RemoteInput 直接回复与 BigPictureStyle 图片预览留作远期。

> 目标：通知从「应用自身事件」（安装完成/装配完成）扩展到「直连 DSH 的对话事件」——agent 回复完成、等待输入、任务出错时推系统通知；同时把通知样式从纯文字升级为 Android 现代通知（MessagingStyle/进度条/聚合/操作按钮），并守住兼容性（minSdk 33）。
> 参考：GitHub 通知模型（reasons 分类 + 按会话分组 + 已读同步）、ntfy（通知 actions）、Android 官方通知 Style 体系。

## 一、现状与差距

- 已有（v2.2.12-beta 本地）：TaskNotifier 发「任务事件」渠道——runtime 安装完成、服务异常、插件装配完成/失败，纯文字。
- 差距一：DSH 对话无感知——agent 在后台跑任务（编译/长对话）完成或需要用户输入时，用户离开浏览器就断感知。
- 差距二：样式单调——无进度条、无会话聚合、无操作按钮。

## 二、事件来源与通路（关键设计）

dsh 会话是**追加式事件日志**，持久化 provider 为 jsonl/sqlite（`dshHome` 下，宿主侧经 hostfs 可见）；事件写路径有界批处理 + 显式 flush 屏障，文件存在可见写入点。据此有三层通路：

| 层级 | 事件 | 通路 | dsh 改动 | 可靠性 |
|---|---|---|---|---|
| 进程级 | 服务启动/退出/崩溃重启 | 壳侧 watchdog（已有） | 无 | 高（已落地） |
| 会话级 | 会话有新活动 | FileObserver 监听会话持久化目录 | 无 | 高，语义保守 |
| 对话级 | turn 完成/等待输入/错误 | 自研 dsh-notification 插件 → 文件投递 | 插件（我们可维护） | 高，语义精确 |

### 2.1 会话级（零 dsh 改动，优先做）
- FileObserver 监听 `dshHome` 下会话持久化目录（jsonl/sqlite 的写路径）。
- 只检测「会话有追加」，解析保守（读最后一行 SessionEvent 的类型/时间戳，不深解析内部格式）：节流去抖（5s）后发「会话有更新」通知，点按打开 WebUI 定位该会话。
- 风险：会话文件内部格式随 dsh 版本变化——**只依赖"文件有写入"这一事实，语义字段全部 try 解析**，失败降级为通用文案。

### 2.2 对话级（dsh-notification 插件，语义精确）
- 自研 dsh 插件（走 dshmarket 插件机制，本仓库 dsh-plugin-builds release 分发，与现有插件分发链一致）：
  - 插件内订阅 agent 事件（turn 完成 / 等待用户输入 / 任务错误 / 子代理完成）；
  - 投递走**文件协议**（沿用 umarm 命令通道的设计决策：hostfs 对 FIFO/网络行为的支持已验证可靠）：插件把语义化事件 JSON 追加写入 `dshHome/.siliconleap-events/<id>.json`，壳侧 FileObserver 捕获并消费后删除；
  - 备选投递：POST 壳侧 localhost HTTP 端点（proot 共享网络栈，guest 127.0.0.1 可达宿主端口）——多一条通路但引入壳侧 HTTP server，文件协议优先。
- 插件随「重新装配缺失插件」链路自动装配，无新分发机制。

## 三、通知样式分级（多样化 + 兼容性）

minSdk 33（Android 13+），官方 Style 全可用。原则：**用系统 Style，不用自定义 RemoteViews**（Android 12+ 通知模板化，自定义视图受限且各 ROM 表现不一）。

| 渠道 | IMPORTANCE | 样式 | 用途 |
|---|---|---|---|
| 服务状态 | LOW（常驻） | 文字 + setProgress 进度条 | 下载/解压实时进度条（现仅文字） |
| 任务完成 | DEFAULT | BigTextStyle / InboxStyle | 安装完成、装配结果（多插件聚合为一条 Inbox 多行摘要） |
| 会话更新 | DEFAULT | BigTextStyle | 会话有新活动（Phase 2） |
| 对话消息 | HIGH（横幅+声音） | **MessagingStyle**（Person + 历史消息） | agent 回复/等待输入（Phase 3），多会话各一条 |
| 图片产出 | HIGH | **BigPictureStyle** | dsh 产出图片文件时通知预览（FileObserver 感知新图片） |

操作按钮（actions）：
- 会话通知：**打开会话**（WebUI 定位）/ **标记已读**（点开后与 WebUI 已读同步，对齐 GitHub 通知模型）
- 装配失败通知：**重试**（触发 ensureBlocking force）
- 服务通知：**停止**（已有）
- 远期：RemoteInput 通知栏直接回复对话（需 dsh 对话发送 API，Phase 3+）

兼容性清单：
- Android 13：POST_NOTIFICATIONS 运行时权限（MainActivity 已请求；未授予静默跳过）
- Android 12+：模板化渲染——统一系统 Style；MessagingStyle 的 Person 用 `setKey` 保证同会话合并
- Android 14+：前台服务类型（已有）；通知动作 PendingIntent 全部 FLAG_IMMUTABLE（已有惯例）
- 通知渠道分级让用户在系统设置可控；应用内不重复提供开关

## 四、分阶段路线

### Phase 1：样式升级（1 天，零风险）
- 下载/解压通知加 setProgress 进度条（RuntimeManager.state 已有 progress 字段）
- 装配结果改 InboxStyle 多行聚合（每插件一行：✓/✗ + 名称）
- 错误类通知改 BigTextStyle（附日志尾部片段，LogStore 已有内容）

### Phase 2：会话级通知（2-3 天）
- FileObserver 监听会话持久化目录（先实地确认 dshHome 内目录结构与写入点：在线装好运行时后人工核实 jsonl/sqlite 路径）
- 「会话有更新」通知（节流 5s，点按直达 WebUI），新渠道「会话更新」DEFAULT
- 已读同步：壳记录已通知的会话 revision，WebUI 打开该会话即清除

### Phase 3：dsh-notification 插件（约 1 周）
- 自研插件：订阅 agent 事件 → 语义化事件文件 → 壳 FileObserver 消费
- 事件类型：turn 完成 / 等待输入 / 错误 / 子代理完成
- MessagingStyle 对话卡（HIGH 渠道，横幅+声音）：sender=DSH，历史消息随通知累积
- 分发：dsh-plugin-builds release，随装配链自动落位

### 远期
- RemoteInput 直接回复；BigPictureStyle 图片预览；IM 桥接（ntfy/Apprise 思路，壳为唯一出口）

## 五、风险

- 会话文件内部格式不稳定 → 语义字段全部 try 解析 + 降级通用文案；深度语义一律走插件通路（dsh 侧解析，壳只消费稳定 JSON 契约）
- FileObserver 在 hostfs 上的事件可靠性 → 以 umarm 命令通道的文件协议经验为参照，落地前做一次高写入频次的实测
- 对话通知噪音 → 渠道分级 + 节流 + 已读同步；HIGH 渠道仅用于「等待输入」类需要用户行动的事件，turn 完成走 DEFAULT
