package com.siliconleap.app.runtime

import android.content.Context
import java.io.File

/** 应用内开关的持久化存储（SharedPreferences 在应用升级时保留）。 */
object AppSettings {
    private const val PREFS = "siliconleap_prefs"
    private const val KEY_AUTO_START = "auto_start_service"
    private const val KEY_AUTO_UPDATE = "auto_update"
    private const val KEY_WORKSPACE_PATH = "workspace_path"

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

    // ------------------------------------------------------------- 下载源

    const val SOURCE_GITHUB = "github"
    const val SOURCE_GHPROXY_AXISNOW = "ghproxy_axisnow"
    const val SOURCE_GHPROXY_CF = "ghproxy_cf"
    const val SOURCE_CUSTOM = "custom"

    private const val KEY_SOURCE = "download_source"
    private const val KEY_CUSTOM_URL = "custom_meta_url"

    /** 当前下载源：github / ghproxy_axisnow / ghproxy_cf / custom。默认 GHProxy Cloudflare（可在设置中修改）。 */
    fun downloadSource(context: Context): String =
        prefs(context).getString(KEY_SOURCE, SOURCE_GHPROXY_CF) ?: SOURCE_GHPROXY_CF

    fun setDownloadSource(context: Context, source: String) {
        prefs(context).edit().putString(KEY_SOURCE, source).apply()
    }

    /** 自定义源 metadata.json URL。 */
    fun customMetaUrl(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_URL, "") ?: ""

    fun setCustomMetaUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_CUSTOM_URL, url).apply()
    }
}
