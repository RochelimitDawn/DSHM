# DSHM v2.1.48

本版本聚焦「反馈与透明」：修复插件装配的退避死锁、运行时版本误报，补齐引导页的动线与 ProRoot 环境预检，插件页新增来源标注与失败状态。

## 修复

- **全部插件装配失败（真正的根因）**：`dsh plugin` 的 pnpm 拉起补丁只打到了 dsh-plugin-manager 的 `lib/index.js`（双引号 import），而 web profile 实际走的是 `lib/types/operations.js` 的 `runPluginCommand`（**单引号 import**，补丁正则未匹配）——execa 原样从 PATH 拉起 `pnpm`，解析到 corepack shim 后尝试运行 pnpm 12.8.1（其 bin 为 `.mjs`，调用方找 `.cjs`）直接 MODULE_NOT_FOUND，所有插件装配 100% 失败。补丁现在覆盖全部 execa 路径（单双引号兼容），并在干净环境下完成端到端验证：`dsh plugin add` 成功、profile bundles 正确 reconcile。
- **git 插件装配失败（dsh-genui / 无限红队 / 破甲）**：proot rootfs 没装 ca-certificates，git 的 https 传输全部验不过（`server certificate verification failed. CAfile: none`）。参考 DSH-Folk 方案修复：用容器 Node 导出 `tls.rootCertificates` 落 pem + git 全局 `http.sslCAInfo`；镜像加速改用 git `insteadOf` 重写，按下载源线路重写、失败回退直连、装完清理。
- **插件重新装配失效**：装配失败的插件会进入 6 小时退避期，此前的「重新装配」会跳过退避中的插件。现在「重新装配」跳过退避强制重试，并在完成后弹提示反馈装配结果。
- **运行时版本误报**：版本比对改读 runtime 构建携带的 `runtime-version` 标记文件；已装旧版运行时的用户会提示更新一次，覆盖安装后提示消失。

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
