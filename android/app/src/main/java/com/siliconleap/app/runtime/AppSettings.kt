package com.siliconleap.app.runtime

import android.content.Context
import java.io.File

/** 应用内开关的持久化存储（SharedPreferences 在应用升级时保留）。 */
object AppSettings {
    private const val PREFS = "siliconleap_prefs"
    private const val KEY_AUTO_START = "auto_start_service"
    private const val KEY_AUTO_UPDATE = "auto_update"
    private const val KEY_WORKSPACE_PATH = "workspace_path"
    private const val KEY_RUNTIME_VERSION = "runtime_version"
    private const val KEY_RUNTIME_INSTALLED = "runtime_installed"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autoStartService(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_START, true)

    fun setAutoStartService(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    fun autoUpdate(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_UPDATE, true)

    fun setAutoUpdate(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply()
    }

    private const val KEY_UPDATER_ENABLED = "updater_enabled"

    /**
     * 更新功能总开关：关闭后不检查更新、不弹更新提示（UpdateDialog/设置页横幅全部静默）。
     * 与 autoUpdate（启动自动检查）独立——本开关是「更新」功能本身的开关。
     */
    fun updaterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UPDATER_ENABLED, true)

    fun setUpdaterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_UPDATER_ENABLED, enabled).apply()
    }

    private const val KEY_ACCEPT_APP_BETA = "accept_app_beta"

    /**
     * 接收 Beta 版 APK 更新：开启后稳定版渠道也会收到 prerelease；
     * 关闭（默认）时跟随安装渠道——安装包版本名含 beta 即收 prerelease，正式版只收正式 release。
     */
    fun acceptAppBeta(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACCEPT_APP_BETA, false)

    fun setAcceptAppBeta(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACCEPT_APP_BETA, enabled).apply()
    }

    /** 用户设定的工作区路径；未设置时为应用私有目录下的 workspace。 */
    fun workspacePath(context: Context): String =
        prefs(context).getString(KEY_WORKSPACE_PATH, null)
            ?: File(context.filesDir, "workspace").absolutePath

    fun setWorkspacePath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_WORKSPACE_PATH, path).apply()
    }

    /** 恢复默认：应用私有目录 workspace。 */
    fun resetWorkspacePath(context: Context) {
        prefs(context).edit().remove(KEY_WORKSPACE_PATH).apply()
    }

    private const val KEY_WORKSPACE_CACHE = "workspace_cache_enabled"

    /**
     * 工作区缓存层：把依赖/缓存目录（node_modules 等）放到原生 fs，
     * 以 bind 覆盖回 guest 原路径。默认开（公共存储 FUSE 元数据慢 5 倍以上）。
     */
    fun workspaceCacheEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WORKSPACE_CACHE, true)

    fun setWorkspaceCacheEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WORKSPACE_CACHE, enabled).apply()
    }

    // ------------------------------------------------------------- 运行时状态缓存

    /** 上次安装/检测到的运行时版本（应用重启后恢复显示，避免"从零开始"观感）。 */
    fun runtimeVersion(context: Context): String? =
        prefs(context).getString(KEY_RUNTIME_VERSION, null)

    fun setRuntimeVersion(context: Context, version: String?) {
        prefs(context).edit().putString(KEY_RUNTIME_VERSION, version).apply()
    }

    fun runtimeInstalled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RUNTIME_INSTALLED, false)

    fun setRuntimeInstalled(context: Context, installed: Boolean) {
        prefs(context).edit().putBoolean(KEY_RUNTIME_INSTALLED, installed).apply()
    }

    // ------------------------------------------------------------- 下载源

    const val SOURCE_AUTO = "auto"
    const val SOURCE_GITHUB = "github"
    const val SOURCE_GHPROXY_AXISNOW = "ghproxy_axisnow"
    const val SOURCE_GHPROXY_CF = "ghproxy_cf"
    const val SOURCE_CUSTOM = "custom"

    private const val KEY_SOURCE = "download_source"
    private const val KEY_CUSTOM_URL = "custom_meta_url"

    /** 当前下载源：auto / github / ghproxy_axisnow / ghproxy_cf / custom。默认自动测速选择。 */
    fun downloadSource(context: Context): String =
        prefs(context).getString(KEY_SOURCE, SOURCE_AUTO) ?: SOURCE_AUTO

    fun setDownloadSource(context: Context, source: String) {
        prefs(context).edit().putString(KEY_SOURCE, source).apply()
    }

    /** 自定义源 metadata.json URL。 */
    fun customMetaUrl(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_URL, "") ?: ""

    fun setCustomMetaUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_CUSTOM_URL, url).apply()
    }

    // ------------------------------------------------------------- Debian 子系统

    private const val KEY_SUBSYSTEM_SHELL = "subsystem_shell_enabled"
    private const val KEY_SUBSYSTEM_FLAVOR = "subsystem_flavor"

    // 仅 Debian：Ubuntu flavor 已移除，发行版切换下线（proot/UML 统一 Debian 镜像包）

    /** DSH shell 命令是否走 Debian 子系统（proot）。默认开启（装即生效）。 */
    fun subsystemShellEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUBSYSTEM_SHELL, true)

    fun setSubsystemShellEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUBSYSTEM_SHELL, enabled).apply()
    }

    // ------------------------------------------------------------- UML 空闲回收

    private const val KEY_UML_IDLE_MINUTES = "uml_idle_minutes"

    /** UML 空闲回收阈值（分钟）；0 = 不回收。默认 5。 */
    fun umlIdleMinutes(context: Context): Int =
        prefs(context).getString(KEY_UML_IDLE_MINUTES, "5")?.toIntOrNull() ?: 5

    fun setUmlIdleMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putString(KEY_UML_IDLE_MINUTES, minutes.toString()).apply()
    }

    // ------------------------------------------------------------- Root Shell

    private const val KEY_ROOT_SHELL = "root_shell_enabled"

    /** DSH shell 命令是否以真 root（su）在宿主 Android 执行。默认关闭。 */
    fun rootShellEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ROOT_SHELL, false)

    fun setRootShellEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ROOT_SHELL, enabled).apply()
    }

    // ------------------------------------------------------------- 运行时测试通道

    private const val KEY_ACCEPT_RUNTIME_BETA = "accept_runtime_beta"

    /** 是否接受测试版运行时更新（滚动通道 runtime-beta-latest）。默认关闭。 */
    fun acceptRuntimeBeta(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACCEPT_RUNTIME_BETA, false)

    fun setAcceptRuntimeBeta(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACCEPT_RUNTIME_BETA, enabled).apply()
    }

    // ------------------------------------------------------------- 子系统引擎

    const val SUBSYSTEM_ENGINE_PROOT = "proot"
    const val SUBSYSTEM_ENGINE_UML = "uml"
    const val SUBSYSTEM_ENGINE_HYBRID = "hybrid"
    const val SUBSYSTEM_ENGINE_AUTO = "auto"

    private const val KEY_SUBSYSTEM_ENGINE = "subsystem_engine"

    /** 子系统引擎：hybrid（新默认）= 按命令动态调度（短命令 proot / 重载 UML）；
     *  auto = UML 优先、不可用回退 proot；已保存的 proroot 值静默迁移为 proot。 */
    fun subsystemEngine(context: Context): String =
        when (prefs(context).getString(KEY_SUBSYSTEM_ENGINE, SUBSYSTEM_ENGINE_HYBRID)) {
            SUBSYSTEM_ENGINE_PROOT -> SUBSYSTEM_ENGINE_PROOT
            SUBSYSTEM_ENGINE_UML -> SUBSYSTEM_ENGINE_UML
            SUBSYSTEM_ENGINE_HYBRID -> SUBSYSTEM_ENGINE_HYBRID
            SUBSYSTEM_ENGINE_AUTO -> SUBSYSTEM_ENGINE_AUTO
            else -> SUBSYSTEM_ENGINE_HYBRID
        }

    fun setSubsystemEngine(context: Context, engine: String) {
        prefs(context).edit().putString(KEY_SUBSYSTEM_ENGINE, engine).apply()
    }

    // ------------------------------------------------------------- 预装插件选择

    private const val KEY_PREINSTALL_PLUGINS = "preinstall_plugins"

    /** 引导时选择的预装兼容插件 id 集合；空集合表示全部安装（默认）。 */
    fun preinstallPlugins(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PREINSTALL_PLUGINS, null) ?: emptySet()

    fun setPreinstallPlugins(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_PREINSTALL_PLUGINS, ids).apply()
    }

    // ------------------------------------------------------------- 首次引导

    private const val KEY_ONBOARDING_DONE = "onboarding_done"

    /** 渐进式引导是否已完成（完成后不再进入引导，直接进主界面）。 */
    fun onboardingDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_DONE, done).apply()
    }

    // ------------------------------------------------------------- 更新说明

    private const val KEY_CHANGELOG_SHOWN = "changelog_shown_version"

    /** 最近一次弹过「本次更新」说明的应用版本（升级后首次打开弹一次）。 */
    fun changelogShownVersion(context: Context): String =
        prefs(context).getString(KEY_CHANGELOG_SHOWN, null) ?: ""

    fun setChangelogShownVersion(context: Context, version: String) {
        prefs(context).edit().putString(KEY_CHANGELOG_SHOWN, version).apply()
    }

    // ------------------------------------------------------------- 运行分区

    const val RUN_MODE_CONTAINER = "container"

    private const val KEY_RUN_MODE = "run_mode"

    /** 运行分区：container（proot 容器，默认）。root 执行由 root_shell_enabled 单独控制。 */
    fun runMode(context: Context): String =
        prefs(context).getString(KEY_RUN_MODE, null) ?: ""

    fun setRunMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_RUN_MODE, mode).apply()
    }

}
