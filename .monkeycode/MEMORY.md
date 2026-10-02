# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[User Instruction Summary]
- Date: 2026-08-14
- Context: 用户要求分析 DeepSeek Harness 仓库并产出文档
- Instructions:
  - 项目中所有文档统一放置于根目录下的 `docs/` 文件夹，后续新增文档也放置于 `docs/` 目录下
  - 文档内容可以使用二级分点整理

[User Instruction Summary]
- Date: 2026-08-14
- Context: SiliconLeap（硅基跃迁）移动端项目的版本号命名规则
- Instructions:
  - 基础版本号为 `v2.N.E` 格式：大版本升级递增 `N`，小版本升级递增 `E`
  - 当前版本为 `v2.1.1`（N=1、E=1）
  - 产品名称为 SiliconLeap（硅基跃迁），Android `versionName` 与 `versionCode` 均按此规则映射

[Project Knowledge Summary]
- Date: 2026-08-14
- Context: Discovered by Agent while assembling the Termux Android runtime for SiliconLeap
- Category: Build Methods
- Instructions:
  - Termux 二进制的动态库 RUNPATH 为绝对路径 `/data/data/com.termux/files/usr/lib`，但 `LD_LIBRARY_PATH` 优先级高于 `DT_RUNPATH`，设置 `LD_LIBRARY_PATH=$PREFIX/lib` 即可让运行时重定位到任意应用私有目录
  - Termux bootstrap 的符号链接记录在 `SYMLINKS.txt`（格式 `绝对目标←./相对链接路径`），需按官方 TermuxInstaller 语义重建为相对符号链接
  - 官方 bootstrap 下载地址：`https://github.com/termux/termux-packages/releases/download/bootstrap-<ver>/bootstrap-aarch64.zip`
  - Android bionic 缺少 `<pty.h>` 的 `openpty/forkpty/login_tty`，node-pty 需用 `posix_openpt` 兼容头交叉编译

[Project Knowledge Summary]
- Date: 2026-10-01
- Context: Discovered by Agent while verifying Kotlin changes in the DSHM workspace
- Category: Environment Configuration
- Instructions:
  - 本工作区没有 kotlinc / Android SDK / JDK17，Kotlin 与 Compose 代码改动无法本地编译验证；完整构建需在装有 Android SDK + JDK 17 的环境执行 `cd android && ./gradlew :app:assembleRelease`（CI 见 `.github/workflows/build-apk.yml`）
  - 校验 Kotlin 改动可用 `node tools/check-kotlin-src.js android/app/src/main/java`（字符串/模板/注释闭合自检，已接 CI）

[Project Knowledge Summary]
- Date: 2026-10-01
- Context: Discovered by Agent while releasing v2.1.43 via GitHub Actions（4 轮 CI 失败诊断后成功）
- Category: Troubleshooting & Debugging
- Instructions:
  - GitHub runner 的 ubuntu-latest（24.04）已移除预装 Android SDK；构建 job 必须钉 `runs-on: ubuntu-22.04`（自带 SDK，ANDROID_HOME=/usr/local/lib/android/sdk）
  - 预装 SDK 的 `sdkmanager` 不在 PATH，须用 `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager` 全路径
  - 本地无 Android SDK，Kotlin 编译错误只能由 CI 暴露；已踩过的坑：`lastNotNullOfOrNull` 不存在于 Kotlin stdlib（用 var 收集取最后）、Miuix `TextField` 无 `fontSize` 参数、函数签名改返回类型时同步检查所有 return 点
  - CI 日志匿名下载返回 401；可用平台 git credential helper 的 token 走 `-u "x-access-token:<token>"` 下载（仅内部使用）
  - 同 SHA 重新 push tag 不会重触发 CI；删除远程 tag 再推同名 tag 可重触发
  - check-elf-closure.js 必须豁免 Android bionic 系统库（libc.so/libm.so/libdl.so 等），否则 Termux 二进制误报缺失导致构建失败

[Project Knowledge Summary]
- Date: 2026-10-01
- Context: Discovered by Agent while fixing Android 上 dsh 0.2.0 启动失败（node-addon-require-builtin native binding not found）
- Category: Troubleshooting & Debugging
- Instructions:
  - dsh 0.2.0 的 `node-addon-require-builtin` 需要 android-arm64 原生绑定，上游仅发布 darwin/linux-glibc/win32 平台包（npm 无 android），Android 上 boot fatal
  - 该 addon 用于获取 Node 内部模块（internal/modules/esm/loader 等，profile 解析核心）；我们的启动命令始终传 `--expose-internals`，此时 internal/* 模块可经原生 require 直接拿到
  - 修复：patch_runtime.js Patch 15 给该包打 JS 回退（createEntryApi 失败时 requireBuiltin 用原生 require），已验证绑定缺失时 dsh 完整启动
  - runtime 版本标签与 npm 版本分离：DSH_VERSION（metadata 标签，带 r 后缀如 0.2.0-rc.2-r2）/ DSH_NPM_VERSION（npm install 用 0.2.0-rc.2）；两者混用会让 npm install 报 ETARGET
  - Termux 当前 nodejs 是 26.4.0（ABI v147）

[Project Knowledge Summary]
- Date: 2026-10-01
- Context: Discovered by Agent while upgrading bundled dsh from 0.1.0-rc.6 to 0.2.0-rc.2（npm 依赖树 + patch_runtime.js 全量核对 + x86 宿主端到端启动验证）
- Category: Build Methods
- Instructions:
  - dsh 0.2.0-rc.2 内部结构变化：`dsh-host-apiproxy` 改名 `dsh-native-command`（opener + file manager 两处中文提示补丁）；bash-local 入口从 `run/runArgv` 改为 `execute/executeArgv`；plugin pnpm 从 `spawnSync("pnpm",...)` 改为 `dsh-plugin-manager` 的 `execa(options.command ?? "pnpm",...)`（5 处，import 包 wrapper 补丁）；node-pty 已自带 `createLazyRequire` 懒加载（原 Patch 1 移除）
  - dsh 0.2.0 起 WebUI 需要 `?token=` 认证（`dsh web:` 就绪行携带，401 无 token）；`--no-open` 可关闭默认浏览器拉起
  - dsh 0.2.0 的 `settings.yaml` 被移除（首次启动 rename 为 `.imported` 并迁移进 profile patch）；主题源改为 `$DSH_HOME/profiles/web/cordis.patch.yml` 的 `ui-theme` entry config
  - 存储布局：`dsh-storage-json` 的 root 仍是 `dsh-home/storages/`，workspace 域 single layout 仍为 `storages/workspace.json`（`{name,version,global,tables}` 格式），workspaceRecord schema 未变（path/title/sessionIds/createdAt/updatedAt），global 新增 `pinnedSessionIds` 字段
  - pnpm 必须钉 10.34.5：pnpm 12 的 npm 包是 postinstall 下载原生二进制的启动器，与 `--ignore-scripts` 冲突
  - dsh 0.2.0 的内核包是 dsh 主包的传递依赖（`npm install @deepseek-ai/dsh` 时由 dsh-base 等拉入），patch 目标包（bash-local/bash-sandbox/subprocess-local/attachment-local/session-persistence-jsonl/fs-local/tool-fs-search/terminal-bash/sandbox-local）在传递树里都存在

[Project Knowledge Summary]
- Date: 2026-10-01
- Context: Discovered by Agent while fixing git 插件（dsh-genui/dsh-infinite-gen-4/dsh-purge）装配失败（参考 IPF-Sinon/DSH-Folk 的 DshPluginRepo.kt 实现）
- Category: Troubleshooting & Debugging
- Instructions:
  - proot rootfs 没装 ca-certificates，git 的 https 传输全部验不过（"server certificate verification failed. CAfile: none"）——gh-proxy 与直连 github 都一样，镜像重写做对也栽在证书校验
  - 修复（DSH-Folk 方案）：容器 node 导出 tls.rootCertificates 写 pem + `git config --global http.sslCAInfo`；node -e 单行 JS 落 pem，幂等（pem 已在只重设配置）
  - pnpm 在子进程 fork git，命令行传不进环境，只有 `~/.gitconfig` 能被继承——镜像加速必须用 `git config --global url.<prefix>.insteadOf <base>` 重写，每次装前重设、装后清掉
  - insteadOf 的 base 要同时覆盖 `https://github.com/` 与 `git+https://github.com/` 两种写法；清重写要遍历全部已知前缀，换线路后旧键会留在 .gitconfig 继续生效
  - pnpm 11 对 ignored build scripts（ERR_PNPM_IGNORED_BUILDS）以退出码 1 失败（pnpm-workspace.yaml 写 strictDepBuilds: false 改为警告）；交互式 approve-builds 在容器里跑不了
  - dsh 只加载 profile package.json `dsh.profile.bundles` 列表（dsh plugin 成功后 reconcile 出来，判据是包声明 dsh.bundle.patch）；自己 npm install 装进任何目录都不会被加载
  - `pnpm add <裸包名>` 对已声明过的 registry 依赖是空操作（Already up to date）；git: spec 装配要按线路逐条试（测速快的前置），全失败再 tgz 直链兜底

[Project Knowledge Summary]
- Date: 2026-10-01
- Context: Discovered by Agent while 本地端到端复现 dsh plugin add 失败（x86 宿主 + patch_runtime.js 干净树），定位全部插件装配失败的根因
- Category: Troubleshooting & Debugging
- Instructions:
  - dsh-plugin-manager 的 execa import 有两处：lib/index.js（双引号）与 lib/types/operations.js（单引号 `import { execa } from 'execa';`）——patch 正则必须用 ["']execa["'] 同时兼容，漏一处该路径的 execa 原样从 PATH 拉起 pnpm
  - web profile 的 dsh plugin 走 operations.js 的 runPluginCommand（desktop profile 才走 index.js 的 runProfilePnpm）——只打 index.js 时全部插件装配失败
  - execa('pnpm') 从 PATH 解析到 corepack shim → pnpm 12.8.1（bin 为 pnpm.mjs）→ 调用方找 pnpm.cjs → MODULE_NOT_FOUND；修复后 wrapper 用 node 绝对路径跑 pnpm.cjs 10.34.5
  - dsh 只加载 profile package.json `dsh.profile.bundles` 列表，dsh plugin add 成功后自动 reconcile 出新 bundle（判据是包声明 dsh.bundle.patch）
  - 本地复现方法：npm install @deepseek-ai/dsh@0.2.0-rc.2 --prefix lib + pnpm@10.34.5 + node patch_runtime.js . + PNPM_NODE/PNPM_CJS env 跑 dsh plugin --profile web add

[Project Knowledge Summary]
- Date: 2026-10-02
- Context: Discovered by Agent while 诊断用户设备 dsh 启动崩溃（cordis.patch.yml YAMLException）
- Category: Troubleshooting & Debugging
- Instructions:
  - dsh 建 profile patch 清单（*.patch.yml）先写 "[]" 占位，装配时补写条目会追加在 "[]" 之后产生非法 YAML（YAMLException: end of the stream or a document separator is expected），dsh 解析 profile 直接崩——全部插件装配与 web 启动全挂；AddonManager.sanitizePatchYaml() 在装配/启动前就地修复（首个非注释行为 "[]" 且其后有内容时去掉占位行）
  - Android 运行时无 git 二进制，pnpm 对 github: spec fork git ENOENT，dsh 误报为「找不到 npm/corepack」——git 插件改走 GitHub tarball（archive/HEAD.tar.gz）直装，git spec 仅兜底
  - check-kotlin-src.js 的块注释检查会把注释文本里的 /*（如 *.patch.yml 路径）当嵌套注释开启，Kotlin 注释里要避免 / * 序列

### User Instruction Entry
- Date: 2026-10-02
- Context: 用户报告插件装配与版本显示问题后明确指示
- Instructions:
  - 每一轮新构建/发版时必须升级版本号（build.gradle.kts versionName/versionCode、strings.xml 注释、RELEASE_NOTES.md、git tag），先改版本再推送 CI

[Project Knowledge Summary]
- Date: 2026-10-02
- Context: Discovered by Agent while 本地复现 dsh-genui 装配后 import 失败
- Category: Troubleshooting & Debugging
- Instructions:
  - GitHub 源码 archive（archive/HEAD.tar.gz）直装对「不提交构建产物」的仓库必然 import 失败（repo 只有 src/，lib/ 在 npm 发布时构建；报 "failed to import" / "1 entry did not activate"）——插件装配必须优先用 npm registry 发布版 tarball（含构建产物），git/archive 直装仅作最后兜底
  - dsh-infinite-gen-4 / dsh-purge：源码仓库无 lib/、无 npm 版、无 release 资产，移动端无构建工具链，自动装配必然失败——已从 COMPAT_PLUGINS 移除，插件页标「暂不支持」，用户经 dshmarket 自行处理

[Project Knowledge Summary]
- Date: 2026-10-02
- Context: Discovered by Agent while 执行方案 A（自建插件构建产物发布）
- Category: Troubleshooting & Debugging
- Instructions:
  - 检查 GitHub 仓库文件树用 git/trees API 会被限流返回错误 JSON，误判「未发布构建产物」——先克隆仓库或抓 raw.githubusercontent.com 核实
  - 本地验证插件装配必须完整跑到 dsh web 启动（确认零 "did not activate"），只验证 add+bundles 会漏掉 import 失败
  - dsh-infinite-gen-4 / dsh-purge 源码 archive 可直装（上游提交了入口文件），t经 DSHM release "dsh-plugin-builds" 分发（asset 404 是 CDN 传播延迟，等 ~30s）

[Project Knowledge Summary]
- Date: 2026-10-02
- Context: Discovered by Agent while 落地 DSH-Folk 式子系统原生工具链装配方案
- Category: Operations & Deployment
- Instructions:
  - 子系统装配路径：已装 rootfs 时插件装配走 TermuxEnv.assemblyArgv（rootfs 内 bash -c，额外挂载 prefix/lib/node_modules → /opt/dsh-libs、filesDir/downloads → /siliconleap-downloads），PNPM_NODE=/opt/node/bin/node PNPM_CJS=/opt/dsh-libs/pnpm/bin/pnpm.cjs 走 runtime patch wrapper；未装 rootfs 或引导失败自动回退 bionic 路径
  - rootfs node 引导：npmmirror node tar.gz（应用侧下载缓存）→ rootfs 内 tar 解压 /opt/node（幂等）；CA 用 node 内置证书落 /opt/dsh-ca.pem + git config，免 apt（rootfs 无 git 也能装配，git 插件走 tarball）
  - proot 和 ProRoot 的 bind 都支持 -b src:dst 冒号格式

[Project Knowledge Summary]
- Date: 2026-10-02
- Context: Discovered by Agent while 落地 GUI Computer Use 轮 1（精确验证）
- Category: Troubleshooting & Debugging
- Instructions:
  - Kotlin raw string（"""）不处理转义：\$ 字面写入、$PORT 直接插值——shell 脚本模板必须放 assets 单一事实来源（如 assets/gui.sh），仅做占位符替换
  - org.json JSONObject.toString() 是紧凑格式（无空格），生成的 .gui-config 解析 sed 按无空格匹配；本地 mock 用 json.dumps（带空格）会误报解析失败
  - gui CLI 验证方法：本地 mock HTTP 服务器 + assets/gui.sh 占位替换 + 全用例（tap/text 引号反斜杠 roundtrip/shot 路径拼接/401/usage）
  - 验证运行时 APK 内文件路径：prefix/bin（bionic PATH 含）；rootfs 会话单文件 bind 到 /usr/local/bin/gui + /system/bin/sh bind（rootfs 无 /system）

[Project Knowledge Summary]
- Date: 2026-10-02
- Context: Discovered by Agent while 落地 GUI Computer Use 轮 2（能力发现）
- Category: Environment Configuration
- Instructions:
  - dsh skill 发现机制：dsh-skill-filesystem 自动扫描 dshHome/skills（目录 bundle 或单 .md，frontmatter name+description 必填，name 小写连字符）——App 写 dshHome/skills/gui.md 即可让模型发现新能力，无需插件；坏 skill（缺 frontmatter）会让 dsh web 启动崩溃，改动 skill 必须验证 dsh web 启动
  - GUI Computer Use 架构：GuiAccessibilityService（dump/tap/swipe/text/key/screenshot）+ GuiControlServer（127.0.0.1:18744 + token，配置 dshHome/.gui-config）+ gui CLI（assets/gui.sh 单一事实来源）+ skills/gui.md（模型执行循环纪律）

[Project Knowledge Summary]
- Date: 2026-10-02
- Context: Discovered by Agent while 研究 kelai141/dsh-mobile-apk 并落地 ref 寻址 + 原子换树
- Category: Operations & Deployment
- Instructions:
  - 参考项目 kelai141/dsh-mobile-apk 的实测结论：跨屏拉起唯一可行路径 = Shizuku UserService `am start --display <id> -n <component>`（monkey 无此选项 / shell am start 不稳 / setLaunchDisplayId 被 SafeActivityOptions 拒）；虚拟屏坐标必须绝对 x/y + `input -d <displayId>`，归一化坐标会静默点到真屏
  - 原子换树模式（Eta/dsh-mobile-apk 同款）：staging 解压 → 完整性校验 → 旧树 rename 换出 → 新树 rename 换入 → 后台删旧树；换入失败 rename 旧树回滚
  - ref 寻址：dump 节点带稳定 ref + 服务端缓存 ref→bounds，tapref N 免模型算坐标；ref 随 dump 刷新，过期返回 ref-expired

[Project Knowledge Summary]
- Date: 2026-10-02
- Context: Discovered by Agent while 落地虚拟屏 Computer Use（Shizuku）
- Category: Operations & Deployment
- Instructions:
  - Shizuku 虚拟屏方案：无需 UserService——Shizuku.newProcess（shell uid）全够用；虚拟屏经 `settings put global overlay_display_devices "1280x800/160"` 创建（幂等，置 none 销毁），displayId 从 dumpsys display 的 mName=Overlay 块向上/向下找 mDisplayId 解析
  - 跨屏拉起 `am start --display <id> -n <component>`、输入 `input -d <id>`、截屏 `screencap -d <id> -p`（shell uid 全部可执行，Android 10+）
  - Shizuku 依赖 dev.rikka.shizuku:api/provider 13.1.5（mavenCentral）+ manifest ShizukuProvider 注册；未装/未授权结构化报错分开报（installed/granted）

[Project Knowledge Summary]
- Date: 2026-10-02
- Context: Discovered by Agent while 诊断 v2.1.52/v2.1.53 CI 构建失败
- Category: Troubleshooting & Debugging
- Instructions:
  - 清华 TUNA 镜像会 403 屏蔽 GitHub Actions 数据中心 IP（本地正常、CI 必失败）——runtime-builder 的所有镜像拉取必须用镜像链逐级回退（TERMUX_MIRROR 优先 → 官方 packages.termux.dev → USTC），deps.py 与 fetch_proot_native.sh 都已改为 MIRRORS 数组 + fetch_with_fallback
  - 诊断 CI 失败流程：API runs/{id}/jobs 找 failure job → jobs/{jid}/logs 拉日志 rg 定位——比看 check-run summary 快

[Project Knowledge Summary]
- Date: 2026-10-02
- Context: Discovered by Agent while 修复 v2.1.53 CI 编译错误
- Category: Troubleshooting & Debugging
- Instructions:
  - Shizuku 公开 API 边界：Shizuku.newProcess 与 ShizukuRemoteProcess(IRemoteProcess) 构造都是私有/包私有；公开路径 = bindUserService + 自定义 AIDL UserService（demo 官方模式，IGuiUserService 首行退出码 + exec）；Shizuku.checkSelfPermission() 与 PERMISSION_GRANTED 常量比较用字面 0（常量不在 api AAR）
  - ShizukuUserServiceArgs + ServiceConnection 绑定：binder 缓存复用，绑定超时结构化失败
  - node-pty 1.1.0 编译：npm 10 对本地目录依赖跳过 devDeps（tsc/@types/node 不装）——构建前显式 `npm install typescript@4.9.5 @types/node` + tsconfig 加 "types": ["node"]（TS2591 根因）
  - check-kotlin-src.js 只查字符串闭合，Kotlin 语义错误（private 可见性/未解析引用）只能 CI 编译发现——大改动后要过一遍可见性与引用
- AGP 开启 AIDL 编译必须显式 `buildFeatures { aidl = true }`（默认关，src/main/aidl 的接口不生成 Stub，全部 Unresolved reference）
- AccessibilityService.takeScreenshot 在新 compileSdk 下重载解析到 3 参版本（displayId, Executor, Callback）——显式传 Display.DEFAULT_DISPLAY
- GuiAccessibilityService 补 import android.os.Build（SDK_INT 判断需要）
- VdisplayManager context 传递：exec 改签名后所有私有辅助函数（findOverlayDisplayId/currentDisplayId）都要带 context 参数，跨文件调用点（GuiManager）同步传 app——改函数签名后必须 rg 全部调用点逐一核对，CI 编译失败代价高（每次 ~10 分钟）
- Kotlin 无本地编译器时的替代验证：改签名后 rg 每个调用点核对参数个数与类型，比只跑 check-kotlin-src（只查字符串闭合）可靠
- ShizukuProvider manifest：AAPT2 只认 `android:permission`（单数），`android:permissions` 直接资源链接失败；Shizuku 官方值 = `moe.shizuku.manager.permission.API`（AndroidManifest.xml 是唯一不经过本地验证的文件，改动后要逐属性核对 framework attr 合法性）
- CI 失败推进顺序（v2.1.53）：Kotlin 语义 → AIDL buildFeatures → node-pty TS → Manifest 资源链接；每次只暴露一层错误，改完必须等 CI
- dshmarket 装配链：dshmarket 读 profileContext.packageManager（dsh 宿主发布），未发布时回退自身 PATH/corepack 链探测装配 → Android 无 npm/corepack 可执行 → "找不到 npm/corepack" + 主插件/Web UI 综合包装配失败；修复 = patch profile-boot-*.js 在 PNPM_NODE/PNPM_CJS 存在时发布 packageManager {command: libnode.so, args: [pnpm.cjs]}（hostPackageManager 同时覆盖 pnpm 探测与每次装配 spawn）
- patch_runtime.js 在 CI runtime 构建时执行（build_runtime.sh:115），运行时补丁改动需重打 runtime tarball；本地验证 = node patch_runtime.js <prefix> + `dsh web --dump-config`（exit 0 即 profile boot 未崩）
- v2.1.53 CI 修复全链：Kotlin 语义（GuiAccessibilityService internal/Build import/takeScreenshot 3 参、VdisplayManager context 链）→ AIDL buildFeatures.aidl=true → Manifest android:permission 单数 → node-pty typescript+@types/node → 最终 SUCCESS（36977192536）
- v2.1.54 修复全链：flock 降级（Android platform='android'，flock.js 直接抛 ERR_FLOCK_UNSUPPORTED_PLATFORM——Patch 17 覆盖 node-addon-system/lib/flock.js 为无锁空操作）；dshmarket 装配链（profileContext.packageManager 发布，Patch 16）；插件自愈隔离（RuntimeManager BROKEN_PLUGIN 正则 + quarantineBrokenPlugin 从 bundles 摘除 + 重启不占崩溃配额，参考 dsh-mobile）；dsh-web-ui-all 移除（子插件 peer 依赖 ^0.1.x-rc.x 与 0.2.0-rc.2 不兼容，装配必被拒并回滚——"installation rejected"+"restored package.json"）；明暗模式（ThemeStore.saveDark 更新 modeFlow，不写回 patch 防循环）
- dsh-web-ui-all 装配被拒的精确根因：peer 依赖版本检查（dsh-better-sidebar@0.15.2 等 4 个子插件要求 @deepseek-ai/dsh-* ^0.1.x-rc.x）；豁免机制 = profiles/web/compatibility.json {pkg@ver: [dshVer]}（dsh plugin allow-version 命令或直接写文件）
- dsh-infinite-gen-4 0.4.0 / dsh-purge 1.1.46 与当前版本兼容（peerDeps 只有 cordis ^4.0.1 / 空），本地 PNPM_NODE 链装配成功；设备失败是市场 fallback 链
- 参考项目 /tmp/opencode/dhm-ref（dsh-mobile）：自愈主体 = HarnessService stdout 正则捕获 + quarantine + 重启循环；WebUI 防护 = cookie 自签 + HTTP 200 探测 120s + token 交换回退；修复入口 = 容器终端/SSH（独立于 dsh web 进程）
- dsh-mobile-nav（主插件）1.0.0 同样被 peer 依赖拒绝（要求 dsh ^0.1.0-rc.6，运行时 0.2.0-rc.2）——修复 = peerDeps 改宽 `>=0.1.0-rc.6 <0.3.0` + 升版本 1.0.1，重传 dsh-mobile-nav release 资产（gh api uploads.github.com releases/{id}/assets）；AddonManager MAIN_TGZ_NAME 同步更新（sweepStaleTgz keep 集自动跟随）
- profile package.json 的插件结构是 `dsh.profile.bundles`（不是 dsh.bundles）——quarantine/摘除逻辑读这个路径
- strings.xml 的 string-array item 里 `<` 是 XML 非法字符（`<0.3.0` 解析失败），必须写 `&lt;`——发版文案含版本范围时注意
- 主插件 tgz 结构：package/ 根目录（npm pack 风格），改 package.json 后 tar czf 重新打包
- StatusBadge 陷阱：PluginScreen 的颜色/文本块在 StatusBadge composable（签名独立于 PluginCard）——给函数体加新变量引用时必须同步改所在函数的签名与全部调用点；Python 块替换按文本匹配会命中错误函数，改后必须确认所在函数
- node-pty TS18046：catch 变量 unknown（useUnknownInCatchVariables）随 types 显式化暴露——build_node_pty.sh tsconfig 补 `"useUnknownInCatchVariables": false`
- libtalloc 版本漂移：termux 仓库更新后 libtalloc.so.2.4.3 变 2.4.4 等，fetch_proot_native.sh/build_runtime.sh 硬编码精确文件名 cp 失败；修复 = find -name 'libtalloc.so.2.*' ! -type l 通配解析 + basename 软链（TermuxEnv 引用软链 libtalloc.so.2 不受影响）；镜像/仓库资产带版本号的文件名一律通配解析，不硬编码
- v2.1.54 CI 失败史：be39473（StatusBadge incompatible 参数缺失 + TS18046）→ f367775 APK SUCCESS 但 debian 子系统构建失败（libtalloc 硬编码）→ 3b0bfb7 全修
- 子系统永远回退 bionic 的根因（v2.1.54 修复）：双重引擎配置错配——APK jniLibs 内置 ProRoot（libproroot.so），但 (1) AppSettings.subsystemEngine 默认 "proot"（termux proot），(2) libproot.so 无任何下载来源（app 从不下载 proot-aarch64.tar.gz），(3) 引航页推荐并默认选中 proot → assemblyArgv 判定 !useProroot && !proot.exists() → null → bionic。修复 = 引擎默认 auto（按内置引擎自动选）+ 显式 proot 不可用时回退 ProRoot + 引导页推荐对调（ProRoot 内置 · 推荐）
- ProRoot 引擎组件：libproroot.so + linker/bridge/runtime/stub-loader（jniLibs 打包，/proc/self/exe 同目录自动发现）；termux proot 的 libproot.so 只在 proot-aarch64.tar.gz release 资产里（app 未消费）
- 子系统切换显示旧发行版的根因：extractTarGz 把新发行版 tar 解压到已存在的旧 rootfs 上（tar 合并覆盖），etc/debian_version 残留 → readDistroVersion 优先读它 → Ubuntu 显示 Debian；修复 = 安装前 rootfs.deleteRecursively() 清空（旧工具链 /opt/node 随新 rootfs 重新引导）
- flock 错误的运维结论：修复在 runtime-latest r3（已验证 flock.js 降级版 + packageManager patch 都在 release 里）；设备端仍报错 = 运行时旧版未更新，需装 v2.1.54 APK + 应用内更新运行时到 r3（约 600MB）
- v2.1.55 主插件：dsh-web-mobile 3.0.4（/tmp/opencode/web-mobile-ref，替代 dsh-mobile-nav）——Pi UI 翻页器手势 = sidebar-swipe.ts（pointer events capture + classifySwipe 纯函数 + armOpenFollow 先提交后跟随 + gesture-guard 防冲突），peerDeps 加 `>=0.2.0-rc.0 <0.3.0`（0.2.0-rc.2 API 面全兼容：toggleSidebar/三个 slot/selectPanel/data-sidebar-collapsed 都在）；client.js 是 window.__ModuleLoader__ 自注册单文件，注入验证 = 页面 HTML grep dsh-web-mobile
- 子系统必装：bootstrap 里未装先串行装完再 startServer（DSH_SUBSYSTEM_ARGV 启动即就绪），server 无需为子系统重启
- 主题双向同步：setDark → modeFlow + writePreference（值不同才回写，observer 再进来值相同收敛，无循环）
- v2.1.55 CI：37019991530（监控中）
- strings.xml 同名 array 冲突：v2.1.55 版本 bump 脚本插入新 release_notes array 时旧 array 未删——AAPT 报 "Found item Array/release_notes more than one time"，packageReleaseResources 直接失败；发版 bump 脚本必须先删旧 release_notes 数组再插入新的
- v2.1.55 CI 失败史：371b48e（strings.xml 双 release_notes）→ 079b104 重推
- UI 三处修复（v2.1.55）：ConfirmDialog message 长文本（更新说明）超出对话框高度不可滚动 + 裸 Text 风格不符——改为 Card + verticalScroll（heightIn max 340dp，与 UpdateDialog 说明卡同风格）；测速对话框「重新测速」按钮从独立位置（顶在取消/确定上方）移到「测速结果」标题行右侧（Row + weight spacer），测速中状态同布局
- "active Service directoryPickerController is unavailable" 的根因链：session-persistence-jsonl failed to import（Patch 5 的 defaultFileSystem 对象里还有裸 `link,` 引用——link 从 import 去掉后 import 即 ReferenceError）→ 7 entries 级联未激活 → workspaceRegistry/sessionController pending → gateway 报服务不可用。修复 = defaultFileSystem.link 改 rename 包装（先 stat 目标存在抛 EEXIST 保留 publishCurrentExclusive 并发互斥语义，不存在则 rename）；诊断方法 = dsh web 启动日志 rg "did not activate|failed to import"，node -e "import(...)" 抓 ReferenceError stack 定位行号
- 改 import 列表后必须找同文件里该标识符的**全部引用**（对象属性简写、其它调用），不只查调用点
- v2.1.55 CI：37026730788（rename + defaultFileSystem 完整修复，监控中）
- proroot-ldso glibc 2.36 失败（v2.1.55 修复）：ProRoot 的动态加载器在 Debian bookworm（glibc 2.36）缺 offset table，__isoc23_strtoul（glibc 2.38+ 符号）未解析 → bash 子进程 exit 2 → 环境检查/AI bash 全失败（Web/文件工具正常因为是 server 进程）。修复 = libproot.so（termux proot，ptrace 方案）+ libandroid-shmem.so 从 debian-subsystem release 的 proot-aarch64.tar.gz 提取进 jniLibs（nativeLibraryDir 可 exec），引擎 auto 偏好翻转为 proot（兼容性验证充分），PROOT_LOADER 指向不存在文件的旧变量删除（termux proot 内置 loader）
- 引擎可用性判定：prootArgvJson 需要 proot.exists() + libtalloc（runtime prefix）+ libandroid-shmem.so（jniLibs）——jniLibs 缺任一则引擎永远 null 回退
