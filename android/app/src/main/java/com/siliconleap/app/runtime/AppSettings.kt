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

    const val SUBSYSTEM_DEBIAN = "debian"
    const val SUBSYSTEM_UBUNTU = "ubuntu"

    /** DSH shell 命令是否走 Debian 子系统（proot）。默认开启（装即生效）。 */
    fun subsystemShellEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUBSYSTEM_SHELL, true)

    fun setSubsystemShellEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUBSYSTEM_SHELL, enabled).apply()
    }

    /** 子系统发行版（debian / ubuntu）。默认 Debian。 */
    fun subsystemFlavor(context: Context): String =
        prefs(context).getString(KEY_SUBSYSTEM_FLAVOR, SUBSYSTEM_DEBIAN) ?: SUBSYSTEM_DEBIAN

    fun setSubsystemFlavor(context: Context, flavor: String) {
        prefs(context).edit().putString(KEY_SUBSYSTEM_FLAVOR, flavor).apply()
    }

    // ------------------------------------------------------------- Root Shell

    private const val KEY_ROOT_SHELL = "root_shell_enabled"

    /** DSH shell 命令是否以真 root（su）在宿主 Android 执行。默认关闭。 */
    fun rootShellEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ROOT_SHELL, false)

    fun setRootShellEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ROOT_SHELL, enabled).apply()
    }

    // ------------------------------------------------------------- 运行分区

    const val RUN_MODE_ROOT = "root"
    const val RUN_MODE_CONTAINER = "container"

    private const val KEY_RUN_MODE = "run_mode"

    /** 用户选择的运行分区：root（真 root 宿主执行）/ container（proot 容器）。空串表示未选择。 */
    fun runMode(context: Context): String =
        prefs(context).getString(KEY_RUN_MODE, null) ?: ""

    fun setRunMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_RUN_MODE, mode).apply()
    }
}
