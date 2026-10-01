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
