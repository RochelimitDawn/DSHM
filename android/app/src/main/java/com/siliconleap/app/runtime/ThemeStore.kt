package com.siliconleap.app.runtime

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主题管理：以 DeepSeek Harness 的 user-settings（`$DSH_HOME/settings.yaml` 的
 * `ui-theme.preference`）为唯一主题源。应用与 Harness 共享该文件，dsh 通过
 * chokidar 热重载，实现双向同步。
 */
object ThemeStore {
    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    private const val PREFS = "siliconleap_prefs"
    private const val KEY_WEB_DARK = "web_dark_theme"

    private val _modeFlow = MutableStateFlow(MODE_SYSTEM)
    val modeFlow: StateFlow<String> = _modeFlow.asStateFlow()

    private val _transition = MutableStateFlow<ThemeTransition?>(null)
    val transition: StateFlow<ThemeTransition?> = _transition.asStateFlow()

    /** 主题切换动画事件：中心点（窗口坐标）+ 目标模式。 */
    data class ThemeTransition(val center: Offset, val mode: String)

    /** 从 Harness settings.yaml 加载当前主题模式。 */
    fun load(context: Context) {
        _modeFlow.value = currentMode(context)
    }

    /** 切换主题：更新应用 UI、触发切换动画、写回 Harness settings.yaml。 */
    fun setMode(context: Context, mode: String, center: Offset? = null) {
        _modeFlow.value = mode
        if (center != null) {
            _transition.value = ThemeTransition(center, mode)
        }
        writePreference(context, mode)
    }

    fun consumeTransition() {
        _transition.value = null
    }

    /** 当前是否深色主题（跟随用户设置的 mode，而非仅系统模式）。 */
    @Composable
    fun isDark(): Boolean {
        val mode by modeFlow.collectAsState()
        val systemDark = isSystemInDarkTheme()
        return when (mode) {
            MODE_DARK -> true
            MODE_LIGHT -> false
            else -> systemDark
        }
    }

    /** 读取 Harness settings.yaml 中持久化的主题模式。 */
    fun currentMode(context: Context): String {
        val file = settingsFile(context)
        if (!file.exists()) return MODE_SYSTEM
        val text = runCatching { file.readText() }.getOrDefault("")
        return parsePreference(text)
    }

    private fun parsePreference(text: String): String {
        var inUiTheme = false
        for (line in text.lines()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("ui-theme:")) {
                inUiTheme = true
                continue
            }
            if (inUiTheme) {
                if (trimmed.startsWith("preference:")) {
                    val value = trimmed.removePrefix("preference:").trim().trim('"').trim('\'')
                    return if (value in listOf(MODE_LIGHT, MODE_DARK, MODE_SYSTEM)) value else MODE_SYSTEM
                }
                if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t")) break
            }
        }
        return MODE_SYSTEM
    }

    private fun settingsFile(context: Context): File = File(TermuxEnv.dshHome(context), "settings.yaml")

    private fun writePreference(context: Context, mode: String) {
        val file = settingsFile(context)
        runCatching {
            file.parentFile?.mkdirs()
            val text = if (file.exists()) file.readText() else ""
            val newText = upsertPreference(text, mode)
            val tmp = File(file.parentFile, "settings.yaml.tmp")
            tmp.writeText(newText)
            if (file.exists() && !file.delete()) return@runCatching
            tmp.renameTo(file)
        }
    }

    private fun upsertPreference(text: String, mode: String): String {
        val lines = text.lines().toMutableList()
        val uiThemeIdx = lines.indexOfFirst { it.trimStart().startsWith("ui-theme:") }
        if (uiThemeIdx == -1) {
            val sb = StringBuilder(text)
            if (text.isNotBlank() && !text.endsWith("\n")) sb.append("\n")
            sb.append("ui-theme:\n  preference: $mode\n")
            return sb.toString()
        }
        var prefIdx = -1
        var insertAt = uiThemeIdx + 1
        for (i in uiThemeIdx + 1 until lines.size) {
            val line = lines[i]
            if (line.isNotBlank()) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("preference:")) {
                    prefIdx = i
                    break
                }
                if (!line.startsWith(" ") && !line.startsWith("\t")) break
                insertAt = i + 1
            }
        }
        if (prefIdx != -1) {
            lines[prefIdx] = lines[prefIdx].replace(Regex("preference\\s*:.*"), "preference: $mode")
        } else {
            lines.add(insertAt, "  preference: $mode")
        }
        return lines.joinToString("\n")
    }

    // ------------------------------------------------------------------ 遗留（兼容旧 WebView 方案）

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveDark(context: Context, dark: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEB_DARK, dark).apply()
    }

    fun readDark(context: Context): Boolean = prefs(context).getBoolean(KEY_WEB_DARK, false)
}
