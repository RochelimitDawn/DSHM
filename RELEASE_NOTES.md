# DSHM v2.1.50

## 修复

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

## 新增

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
