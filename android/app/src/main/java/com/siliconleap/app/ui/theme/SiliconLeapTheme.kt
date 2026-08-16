package com.siliconleap.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import com.siliconleap.app.R
import com.siliconleap.app.runtime.ThemeStore
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 全局字体：Aa 古典刻北宋油墨版。 */
private val BrandFontFamily = FontFamily(
    Font(R.font.aa_gudian_ke_ben_song_you_mo_ban),
)

/** 品牌主色（浅色模式）。 */
private val BrandKeyColor = Color(0xFF4D6BFE)

/** 品牌主色（深色模式，比浅色更亮以适配深色背景）。 */
private val BrandKeyColorDark = Color(0xFF7C8DFF)

/** 浅色模式次要文本色（80% 黑，替代默认 60% 黑，白天更清晰）。 */
private val LightSummary = Color(0xCC000000)

/** 深色模式次要文本色。 */
private val DarkSummary = Color(0xCCFFFFFF)

/** 黑白主题：以 Harness 的 ui-theme.preference 为源，跟随系统 / 浅色 / 深色。 */
@Composable
fun SiliconLeapTheme(content: @Composable () -> Unit) {
    val mode by ThemeStore.modeFlow.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (mode) {
        ThemeStore.MODE_DARK -> true
        ThemeStore.MODE_LIGHT -> false
        else -> systemDark
    }
    val colorSchemeMode = when (mode) {
        ThemeStore.MODE_DARK -> ColorSchemeMode.Dark
        ThemeStore.MODE_LIGHT -> ColorSchemeMode.Light
        else -> ColorSchemeMode.System
    }
    val lightColors = remember {
        lightColorScheme().copy(
            primary = BrandKeyColor,
            onSurfaceVariantSummary = LightSummary,
            onSurfaceVariantActions = Color(0x99000000),
            onSurfaceContainerVariant = Color(0xFF757575),
        )
    }
    val darkColors = remember {
        darkColorScheme().copy(
            primary = BrandKeyColorDark,
            onSurfaceVariantSummary = DarkSummary,
            onSurfaceVariantActions = Color(0x99FFFFFF),
            onSurfaceContainerVariant = Color(0xFFA0A0A0),
        )
    }
    val controller = ThemeController(
        colorSchemeMode,
        lightColors = lightColors,
        darkColors = darkColors,
        keyColor = null,
        isDark = darkTheme,
        paletteStyle = ThemePaletteStyle.TonalSpot,
        colorSpec = ThemeColorSpec.Spec2021,
    )
    val fontFamily = remember { BrandFontFamily }
    val textStyles = remember(fontFamily) {
        val defaults = defaultTextStyles()
        fun TextStyle.withFont(): TextStyle = copy(fontFamily = fontFamily)
        defaults.copy(
            main = defaults.main.withFont(),
            paragraph = defaults.paragraph.withFont(),
            body1 = defaults.body1.withFont(),
            body2 = defaults.body2.withFont(),
            button = defaults.button.withFont(),
            footnote1 = defaults.footnote1.withFont(),
            footnote2 = defaults.footnote2.withFont(),
            headline1 = defaults.headline1.withFont(),
            headline2 = defaults.headline2.withFont(),
            subtitle = defaults.subtitle.withFont(),
            title1 = defaults.title1.withFont(),
            title2 = defaults.title2.withFont(),
            title3 = defaults.title3.withFont(),
            title4 = defaults.title4.withFont(),
        )
    }
    MiuixTheme(controller = controller, textStyles = textStyles) {
        CompositionLocalProvider(
            LocalContentColor provides MiuixTheme.colorScheme.onBackground,
        ) {
            content()
        }
    }
}
