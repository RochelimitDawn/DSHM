# DSHM v2.1.57

## 新增

- **WebUI 看门狗**（参考 DSHA HarnessService）：daemon 线程每 15s TCP 探测 WebUI 端口（3s 超时），连续 3 次失联自动重启（120s 冷却防风暴）；userStopRequested 守卫保证看门狗不撤销用户手动停止意图；下载/安装期间（phase != RUNNING）不干预。
- **虚拟屏升级为真实 createVirtualDisplay**（参考 LittleWhale/LwVirtualDisplay）：优先 shell uid 进程内创建虚拟屏，隐藏 flag 字面量组合（TRUSTED=1<<10、OWN_DISPLAY_GROUP、ALWAYS_UNLOCKED（独立于锁屏）、OWN_FOCUS + STEAL_TOP_FOCUS_DISABLED（持焦点不抢主屏）、TOUCH_FEEDBACK_DISABLED，按 Android 版本门控 13/14）——displayId 直接返回免 dumpsys 解析，activity 能落上去；失败自动回退现有 overlay_display_devices 路径，双路线互为降级。AIDL 加 createDisplay/releaseDisplay，Shizuku UserService version 1→2 强制服务进程重启加载新代码。

# DSHM v2.1.56

## 移除

- **ProRoot 引擎全链下线**：LD_PRELOAD + 二进制补丁方案兼容性太差——动态加载器在 glibc 2.36（Debian bookworm）缺 offset table（`proroot-ldso: no offset table`），`__isoc23_*` glibc 2.38+ 符号未解析导致 bash 子进程 exit 2、AI 的 bash/环境检查全部失败；上游（coderredlab/proroot）无公开源码无法补映射，同源方案（DSHA）也靠 proot 兜底续命。移除内容：jniLibs 5 件 .so（约 0.7MB）、TermuxEnv 的 prorootArgvJson/probeProRoot/PROROOT 分支、SubsystemManager.isProRootAvailable、RuntimeManager 探测降级块、引导页 ProRoot 选项与全部文案。proot（termux，ptrace 方案）成为唯一引擎；已保存 proroot 引擎的用户启动时静默迁移为 proot。远期由 UML（linux-um-arm64 预编译内核 + umnetx 网络栈）替换 proot 本体。

## 修复

- **gui 命令行工具未随会话注入**：gui 脚本只在 Application 启动时写入 prefix/bin，运行时更新解压会重建 prefix/bin，脚本被清掉后 server 重启时 gui bind 被跳过（`.gui-config` 在而可执行体不在，会话里 gui 全部 command not found）。修复：ensureCli 在每次 server 启动前重新写入，失败记日志。

# DSHM v2.1.55

## 新增

- **主插件升级 dsh-web-mobile 3.0.4**（替代 dsh-mobile-nav，peer 适配 0.2.0-rc）：Pi UI 翻页器左右滑动手势（侧栏抽屉边缘滑出/滑入跟随、Files 面板手势、轴向锁定 + 距离/速度双阈值）、19 个移动端效果、移动外壳、会话长按删除。tgz 经 dsh-plugin-builds release 分发，端到端验证装配、bundle 回填、web 启动与 client bundle 注入。
- **子系统必装**：首次启动先装完子系统再启动 server（DSH_SUBSYSTEM_ARGV 启动即就绪），装配/终端走 ProRoot rootfs 原生工具链，消除「先起 bionic server 后补装」的过渡态。

## 修复

- **运行 DSH 本轮失败（rename is not defined）**：session-persistence 的 import 补丁只命中调用替换（import 字段顺序随版本漂移，`link, lstat, mkdir, …` 硬编码 `link, mkdir` 不匹配），rename 未 import 每次保存会话就崩。改为通用 import 列表替换（去 link 补 rename）。
- **子系统切换发行版显示旧版**：安装前清空旧 rootfs（tar 合并覆盖残留 etc/debian_version，Ubuntu 显示为 Debian）。
- **子系统引擎错配**：APK 内置 ProRoot 但默认引擎是 proot（libproot.so 无下载来源）——引擎默认 auto 按内置引擎自动选择，显式 proot 不可用时回退 ProRoot，引导页推荐对调。
- **WebUI 明暗模式双向同步**：WebUI 切主题同步更新壳 UI 并写回主题源（壳重启后保持一致），壳切主题 WebUI chokidar 热重载跟随。
- **flock is not supported on android-arm64**：bionic node 无原生 flock，降级为无锁空操作。
- **插件市场装配失败**：profile boot 从 PNPM_NODE/PNPM_CJS 发布 packageManager，dshmarket 的探测与装配与终端同链。

# DSHM v2.1.54

## 修复

- **运行 DSH 本轮失败（flock）**：Android（bionic node）无原生 flock 可加载，会话创建/本轮运行直接失败。flock 降级为无锁空操作（单用户移动端无跨进程会话竞争）。
- **插件市场/主插件装配失败（dshmarket 装配链）**：dsh server 启动不发布 `packageManager`，dshmarket 回退自身 PATH/corepack 链探测 pnpm——Android 上无 npm/corepack 可执行，探测失败 + 市场内装配全部失败。现在 profile boot 在 PNPM_NODE/PNPM_CJS 存在时发布 packageManager（node+pnpm.cjs 直连），dshmarket 的探测与装配 spawn 与终端同链。
- **移除 WebUI 综合适配包（dsh-web-ui-all）**：拉进的子插件（dsh-better-sidebar、@morlay/session-*）peer 依赖要求 dsh ^0.1.x-rc.x，与 0.2.0-rc.2 运行时不兼容，装配必被拒并整体回滚。
- **WebUI 明暗模式同步失效**：WebUI 切主题经 JS observer 同步到壳时只存配置、壳 UI 不更新。现在 setDark 同步更新 modeFlow，壳 UI 跟随 WebUI 主题。

- **主插件（dsh-mobile-nav）装配被拒**：1.0.0 的 peer 依赖要求 dsh ^0.1.0-rc.6，与 0.2.0-rc.2 运行时不兼容。peer 依赖改宽到 `<0.3.0` 并升版本 1.0.1（release 资产已更新），本地端到端验证装配与 web 启动通过。

## 新增

- **插件自愈（隔离重启）**：坏插件拖垮 dsh web 时，从 stdout 捕获 loader 导入失败行解析包名，进程退出后自动从 profile bundles 摘除并重启（保留 node_modules 文件，重装可回填）——WebUI 恢复可用，单点失败不连坐。

## 改进

- 装配因 peer 依赖不兼容被运行时拒绝时显示「不兼容」状态（此前显示「装配失败」），与网络/超时失败区分。

# DSHM v2.1.53

## 修复

- **无限红队 / 破甲恢复自动装配**：两个插件的上游仓库实际提交了入口文件（此前检查被 API 限流误报为「未发布构建产物」），源码 tgz 直装端到端验证装配、import 与 web 启动全部通过。tgz 已发布到本仓库 `dsh-plugin-builds` release（稳定 + 加速线路兼容），自动装配完整覆盖全部 7 个插件。
- **dsh-genui 换用 npm 发布版**：GitHub 源码 archive 不含构建产物 lib/，改用 npm @changfenhuang/dsh-genui 0.11.3（含 lib/，端到端验证通过）。

- **dsh-genui 装配后 import 失败**：GitHub 源码 archive 不含构建产物 lib/（repo 只提交 src，lib 在 npm 发布时构建），源码直装后 `main: lib/index.js` 必然 import 失败（`1 entry did not activate`）。改用 npm 发布版 @changfenhuang/dsh-genui 0.11.3（含 lib/，本地端到端验证装配成功且 bundle 正确 reconcile）。
- **无限红队 / 破甲标记「暂不支持」**：两个仓库都未发布构建产物（无 lib/、无 npm 版、无 release 资产），移动端无构建工具链，自动装配必然失败。已从自动装配清单移除，插件页标注「暂不支持」，可经 dshmarket 手动处理。

- **运行时版本显示混乱**：重启应用后版本回退显示 `0.2.0-rc.2` 并误报更新。版本读取优先级改为 runtime 标记文件 → 上次下载持久化的 metadata 版本 → dsh 包版本；旧 r2 运行时（无标记文件）重启后如实显示 r2，更新横幅保持提示。
- **重型插件装配失败（UI 全能优化 / UI 适配）**：`dsh plugin add` 超时 90s 太短——dsh-web-ui-all 装配要拉约 20 个依赖，移动端网络下远超 90s，超时即记失败。装配超时放宽到 180s，装配后验证（临时端口冷启动）放宽到 120s。
- **插件市场版本过期**：dshmarket 1.66.7 → 1.66.8。
- **git 插件装配失败（dsh-genui / 无限红队 / 破甲）**：Android 运行时没有 git 二进制，pnpm 对 `github:` spec fork `git` 直接 ENOENT，dsh 把失败误报为「找不到 npm/corepack」。git 插件改为 GitHub tarball 直装（`archive/HEAD.tar.gz`，完全免 git，按下载源走加速线路），git spec（含 CA 证书修复）仅作兜底。
- **全部插件装配失败（真正的根因）**：`dsh plugin` 的 pnpm 拉起补丁只打到了 dsh-plugin-manager 的 `lib/index.js`（双引号 import），而 web profile 实际走的是 `lib/types/operations.js` 的 `runPluginCommand`（**单引号 import**，补丁正则未匹配）——execa 原样从 PATH 拉起 `pnpm`，解析到 corepack shim 后尝试运行 pnpm 12.8.1（其 bin 为 `.mjs`，调用方找 `.cjs`）直接 MODULE_NOT_FOUND，所有插件装配 100% 失败。补丁现在覆盖全部 execa 路径（单双引号兼容），并在干净环境下完成端到端验证。
- **profile patch 清单破损（致命）**：dsh 建 patch 清单时先写 `[]` 占位，装配时补写条目追加在 `[]` 之后产生非法 YAML，dsh 解析 profile 直接崩（全部装配与 web 启动全挂）。每次装配/验证/启动前就地修复破损清单。
- **运行时更新点击无效果**：更新横幅点击后 `installRuntime()` 在运行时已存在时被「无需重复下载」早退拦截。现在横幅点击强制重下并覆盖安装（约 500 MB）。
- **插件重新装配失效**：失败退避中的插件现在强制重试，完成后弹提示反馈装配结果。
- **运行时版本误报**：版本比对改读 runtime 构建携带的 `runtime-version` 标记文件。

- **主插件（dsh-mobile-nav）装配被拒**：1.0.0 的 peer 依赖要求 dsh ^0.1.0-rc.6，与 0.2.0-rc.2 运行时不兼容。peer 依赖改宽到 `<0.3.0` 并升版本 1.0.1（release 资产已更新），本地端到端验证装配与 web 启动通过。

## 新增

- **虚拟屏 Computer Use（Shizuku，参考 dsh-mobile-apk 实测结论）**：第三方 App 在独立虚拟屏运行，不挤占用户前台——WebUI 聊天与 GUI 操作真并行。`gui vcreate/vdestroy`（overlay_display_devices 模拟显示，shell uid 有 WRITE_SECURE_SETTINGS）、`gui vlaunch <component> [display]` 跨屏拉起（实测唯一可行路径 = shell uid 的 `am start --display <id> -n <component>`）、`gui vtap/vswipe/vtext/vkey <display> …` 跨屏输入（绝对坐标 + `input -d`，归一化坐标明确拒绝——分母歧义会静默点到真屏）、`gui vshot <display>` 跨屏截屏（`screencap -d`）。Shizuku 未装/未授权结构化报错并给指引，skill 同步更新（虚拟屏优先，无 Shizuku 回退 key home 流程）。
- **ref 寻址（参考 dsh-mobile-apk）**：`gui dump` 为每个节点分配稳定 ref，`gui tapref N` 按 ref 点按（服务端缓存 bounds 取中心，免模型手算坐标）；ref 随 dump 刷新，过期返回 `ref-expired` 提示重新检索；skill 执行循环同步更新。
- **运行时单事务原子换树（参考 dsh-mobile-apk / Eta）**：解压到 staging → 完整性校验（dsh 入口缺失即拒绝换树）→ 旧树改名换出 → 新树换入 → 后台清理；换入失败自动回滚旧运行时，中断不再装出半棵树，prefix 外的用户数据（会话/设置/凭据）从不触碰。
- **GUI Computer Use（轮 2）：能力发现 + 执行循环纪律**——App 自动写入 `dshHome/skills/gui.md`（dsh-skill-filesystem 自动发现的 skill，frontmatter 校验过），模型经 skill 索引发现 `gui` 命令与 Computer Use 执行循环（先 dump 找控件、坐标取 bounds 中心、动作后重新 dump 验证、多候选请用户确认、服务未连接/支付密码类界面结构化告知）；设置页新增「GUI 控制（无障碍设置入口）」与「GUI 点按与输入」开关。本地验证：dsh web 以 skill 存在时正常启动（坏 skill 会崩溃），frontmatter 过 skill-filesystem 解析。
- **GUI Computer Use（轮 1，参考 Eta GUI Agent 链路）**：dsh 会话可直接操作 Android 屏幕——`gui dump`（结构化 UI 树，有界：节点数/深度/文本长度，跳过密码框）、`gui tap/longpress/swipe/text/key`（dispatchGesture 同步等待）、`gui shot`（无障碍截屏原始 PNG，不缩放不转 JPEG）。控制通道仅绑定 127.0.0.1 + token 鉴权（配置落 dshHome/.gui-config）；无障碍服务未连接结构化失败（503），tap 类动作带用户开关；不记录请求参数与结果（日志脱敏）。CLI 由 assets/gui.sh 单一事实来源生成，bionic 与 rootfs 会话（单文件 bind）均可用。

- **子系统原生工具链装配（DSH-Folk 方案，高性能落地）**：已安装子系统时，插件装配自动走 rootfs 内的原生 Linux 工具链——引导一份 Linux node v22（npmmirror 镜像，一次约 25MB，缓存复用）+ 挂载 dsh 运行时进 rootfs，真实 node/pnpm/CA 一条链全通，绕开 Android 裸环境的 noexec/无 git/无 CA 全部限制；未装子系统或引导失败自动回退原生路径，服务不受影响。

- **插件装配过程实时反馈**：插件页顶部实时显示正在装配的插件、当前步骤（装配 / 验证）与进度（n/7），装配完成自动消失。
- **测速页面 logo**：下载源 logo 全部换 LobeHub Icons 官方资源（Cloudflare 双色 / GitHub Octocat）+ gh-proxy 官方 logo，矢量渲染主题自适应。

## 改进

- **插件装配逐个验证（参考 DSH-Folk）**：装配逻辑从「全部装完一次性验证、失败整批回滚」改为「主插件先装先验基线，兼容插件逐个装+验」——单个坏插件（如某插件大版本升级后加载崩溃）只回滚自己并标记失败，主插件与其它好插件正常落位，不再连坐。
- **下载后台继续**：运行时 / 子系统 / 插件下载期间自动启动前台服务保活进程——退出应用下载不再中断，通知条实时显示下载进度（百分比与速度）；断点续传作为兜底仍然保留。
- **插件页状态徽章**：装配状态细分为「已装配 / 待装配 / 装配失败」（失败为红色徽章，表示正处于退避期），一眼区分「还没装」与「装失败」。
- **插件来源标注**：每个插件卡片底部标注来源——内置（dsh-external/dsh-mobile-nav）、npm（`@linxin666/dsh-web-ui-all` / `dshmarket` / `dsh-usage-stats`）、GitHub 社区仓库（`omdsh-dev/dsh-genui`、`Minglink/dsh-infinite-gen-4`、`YuJunZhiXue/dsh-purge`）。
- **引导页动线**：步骤切换改为方向感知滑动转场（前进从右滑入、后退从左滑入，淡入淡出错峰），顶部进度条与指示点随步骤平滑填充/放大，切换连贯、反馈明确。
- **ProRoot 环境预检**：引导的引擎选择步骤现在直接显示 ProRoot 是否可用于当前设备（arm64-v8a + 内置 .so 预检）；不支持时选项置灰并标注「此设备不可用」，先告知再选择，避免选完又自动回退的困惑。

## 运行时

- 内置 DeepSeek Harness **0.2.0-rc.2**（npm latest），运行时 **0.2.0-rc.2-r2**（含 node-addon-require-builtin 回退修复）。
- 运行时 zip 新增 `runtime-version` 标记文件，随下一次运行时更新分发。

## 升级说明

- 支持 Android 8.0+（arm64-v8a）；免费、免 root；覆盖安装即可升级，数据与插件全部保留。
- v2.1.47 用户升级后：若插件仍显示「待装配」或红色「装配失败」，到「插件」页点击「重新装配」强制重试；git 插件的证书修复随本次装配流程自动生效。
- 运行时更新提示：升级应用后到「环境」页重新下载一次运行时（本次运行时构建携带版本标记），此后版本检测恢复准确。

## 反馈

遇到问题请附上「环境」页日志（运行日志 / 插件装配日志）并在 [Issues](https://github.com/RochelimitDawn/DSHM/issues) 反馈。
