package com.siliconleap.app.runtime

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

/** Web UI 明暗主题偏好的持久化与读取（启动页与 Harness WebView 联动）。 */
object ThemeStore {
    private const val PREFS = "siliconleap_prefs"
    private const val KEY_WEB_DARK = "web_dark_theme"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 保存 Web UI 当前生效的暗色偏好（由 WebView 主题监听回调写入）。 */
    fun saveDark(context: Context, dark: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEB_DARK, dark).apply()
    }

    /** 读取主题：优先 Web UI 保存的偏好；从未保存过则跟随系统暗色。 */
    fun readDark(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.contains(KEY_WEB_DARK)) return prefs.getBoolean(KEY_WEB_DARK, false)
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }
}
