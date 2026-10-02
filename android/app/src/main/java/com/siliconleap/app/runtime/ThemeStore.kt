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

    /** 读取 Harness 中持久化的主题模式。0.2.0 起 settings.yaml 被移除，主题源为
     *  profile patch 的 ui-theme entry config；未迁移的旧 settings.yaml 作为回退。 */
    fun currentMode(context: Context): String {
        val patch = profilePatchFile(context)
        if (patch.exists()) {
            val v = runCatching { parsePatchPreference(patch.readText()) }.getOrNull()
            if (v != null) return v
        }
        val file = settingsFile(context)
        if (!file.exists()) return MODE_SYSTEM
        val text = runCatching { file.readText() }.getOrDefault("")
        return parsePreference(text)
    }

    private fun profilePatchFile(context: Context): File =
        File(TermuxEnv.dshHome(context), "profiles/web/cordis.patch.yml")

    /** 从 profile patch 解析 `- id: ui-theme` entry 的 preference。 */
    private fun parsePatchPreference(text: String): String? {
        var inEntry = false
        var inConfig = false
        for (line in text.lines()) {
            val trimmedStart = line.trimStart()
            if (line.startsWith("- id:") || line.startsWith("- name:")) {
                inEntry = line.startsWith("- id: ui-theme") ||
                    line.startsWith("- id: 'ui-theme'") ||
                    line.startsWith("- id: \"ui-theme\"")
                inConfig = false
                continue
            }
            if (!inEntry) continue
            if (line.isNotBlank() && !line.startsWith(" ")) break
            if (trimmedStart.startsWith("config:")) { inConfig = true; continue }
            if (inConfig && trimmedStart.startsWith("preference:")) {
                val v = trimmedStart.removePrefix("preference:").trim().trim('"').trim('\'')
                return if (v in listOf(MODE_LIGHT, MODE_DARK, MODE_SYSTEM)) v else null
            }
        }
        return null
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

    /** 写回主题：0.2.0 的主题源是 profile patch 的 ui-theme entry config（chokidar 热重载）。 */
    private fun writePreference(context: Context, mode: String) {
        val file = profilePatchFile(context)
        runCatching {
            file.parentFile?.mkdirs()
            val text = if (file.exists()) file.readText() else ""
            val newText = upsertPatchPreference(text, mode)
            val tmp = File(file.parentFile, "cordis.patch.yml.tmp")
            tmp.writeText(newText)
            if (file.exists() && !file.delete()) return@runCatching
            tmp.renameTo(file)
        }
    }

    /** 在 patch 的 ui-theme entry 中 upsert `config: preference:`（block 与 inline 两种风格都处理）。 */
    private fun upsertPatchPreference(text: String, mode: String): String {
        val lines = text.lines().toMutableList()
        val idIdx = lines.indexOfFirst { it.startsWith("- id: ui-theme") }
        if (idIdx == -1) {
            val sb = StringBuilder(text)
            if (text.isNotBlank() && !text.endsWith("\n")) sb.append("\n")
            sb.append("- id: ui-theme\n  config:\n    preference: $mode\n")
            return sb.toString()
        }
        // entry 边界：下一个 0 缩进的行
        var endIdx = lines.size
        for (i in idIdx + 1 until lines.size) {
            val l = lines[i]
            if (l.isNotBlank() && !l.startsWith(" ")) { endIdx = i; break }
        }
        val configIdx = (idIdx + 1 until endIdx).firstOrNull { lines[it].trimStart().startsWith("config:") }
        if (configIdx == null) {
            lines.add(idIdx + 1, "  config:")
            lines.add(idIdx + 2, "    preference: $mode")
            return lines.joinToString("\n")
        }
        val cfgLine = lines[configIdx].trimStart()
        if (cfgLine.startsWith("config: {") || cfgLine.startsWith("config:{")) {
            val inline = Regex("""config:\s*\{(.*)\}\s*$""")
            val m = inline.find(lines[configIdx])
            if (m != null) {
                val body = m.groupValues[1]
                val newBody = if (body.contains("preference:")) {
                    body.replace(Regex("""preference\s*:\s*[^,}]+"""), "preference: $mode")
                } else if (body.isBlank()) {
                    "preference: $mode"
                } else {
                    "$body, preference: $mode"
                }
                lines[configIdx] = lines[configIdx].replace(inline, "config: { $newBody }")
                return lines.joinToString("\n")
            }
        }
        // block style：config: 下的 preference 行（到下一个同级 key 为止）
        for (i in configIdx + 1 until endIdx) {
            val l = lines[i]
            val t = l.trimStart()
            if (t.startsWith("preference:")) {
                lines[i] = l.replace(Regex("""preference\s*:.*"""), "preference: $mode")
                return lines.joinToString("\n")
            }
            if (t.isNotBlank() && l.startsWith("    ") && !l.startsWith("      ")) break
        }
        lines.add(configIdx + 1, "    preference: $mode")
        return lines.joinToString("\n")
    }

    // ------------------------------------------------------------------ 遗留（兼容旧 WebView 方案）

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** WebUI 主题变化（JS observer）同步到壳（双向）：
     *  壳 → WebUI：setMode 写 patch 文件（chokidar 热重载）。
     *  WebUI → 壳：更新 modeFlow 让壳 UI 跟随，并写回 patch 文件持久化主题源
     *  （壳重启后 WebUI 主题保持一致）。回写经 patch → chokidar → ThemePresenter →
     *  observer 再进来时值相同，setDark 去重后不再回写，循环收敛。 */
    fun saveDark(context: Context, dark: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEB_DARK, dark).apply()
        val mode = if (dark) MODE_DARK else MODE_LIGHT
        if (_modeFlow.value != mode) {
            _modeFlow.value = mode
            writePreference(context, mode)
        }
    }

    fun readDark(context: Context): Boolean = prefs(context).getBoolean(KEY_WEB_DARK, false)
}
