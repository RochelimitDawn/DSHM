package com.siliconleap.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siliconleap.app.runtime.AddonManager
import com.siliconleap.app.runtime.InstallProgress
import com.siliconleap.app.runtime.ThemeStore
import com.siliconleap.app.ui.component.BlurredBar
import com.siliconleap.app.ui.component.ConfirmDialog
import com.siliconleap.app.ui.component.rememberBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 插件页：管理 DSH 的 WebUI 插件（参考 DSH-Folk 插件页，miuix 风格）。
 * - 已装/待装状态一览（主插件 + 兼容插件清单）
 * - 重新装配：全量装配缺失插件，装完临时端口验证插件树能否加载
 * - 每个插件的定位说明
 */
@Composable
fun PluginScreen(bottomInnerPadding: Dp, isActive: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(enableBlur = true)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    var showReassemble by remember { mutableStateOf(false) }

    // rememberUpdatedState：Pager 预组合但不可见时轮询只空转，不触发重组与 backdrop 重录
    val currentActive by rememberUpdatedState(isActive)
    // 装配状态代次：每次 +1 触发重新读取（轻量 IO，仅激活时）
    var stateEpoch by remember { mutableIntStateOf(0) }
    // 装配进度实时反馈：正在装配哪个插件、当前步骤、第几个/共几个
    val progress by AddonManager.installProgress.collectAsState()
    val installedIds by produceState(emptySet(), stateEpoch) {
        while (true) {
            if (currentActive) {
                value = AddonManagerCompat.installedIds()
                if (value.size >= AddonManagerCompat.autoInstallable.size) break
            }
            kotlinx.coroutines.delay(3000)
        }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = "插件",
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
            ) {
                item { SectionTitle("WebUI 插件") }
                progress?.let { p ->
                    item { InstallProgressCard(p) }
                }
                item {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Extension,
                                contentDescription = "插件",
                                tint = colorScheme.onBackground,
                            )
                            Text(
                                text = "已装 ${installedIds.size}/${AddonManagerCompat.autoInstallable.size} · 主插件 + 兼容插件",
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                        top.yukonga.miuix.kmp.basic.TextButton(
                            text = "重新装配缺失插件",
                            onClick = { showReassemble = true },
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .fillMaxWidth(),
                        )
                        Text(
                            text = "装配会用 pnpm 安装插件并做一次临时端口验证，不通过自动卸载；失败后 6 小时内不重试。",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                }
                item { SectionTitle("插件清单") }
                items(AddonManagerCompat.pluginDescriptions.size) { i ->
                    val entry = AddonManagerCompat.pluginDescriptions[i]
                    PluginCard(
                        entry = entry,
                        installed = entry.id in installedIds,
                        failed = AddonManagerCompat.isFailed(entry.id),
                    )
                }
                item { Spacer(Modifier.height(bottomInnerPadding + 12.dp)) }
            }
        }
    }

    ConfirmDialog(
        show = showReassemble,
        title = "重新装配插件",
        message = "将装配所有缺失的 WebUI 插件（需要 dsh 与 node 已就绪）。已装配的插件不受影响；失败退避中的插件会强制重试。标注「暂不支持」的插件不参与自动装配。",
        confirmText = "装配",
        onConfirm = {
            showReassemble = false
            scope.launch {
                val ok = withContext(Dispatchers.IO) { AddonManager.ensureBlocking(force = true) }
                stateEpoch++
                Toast.makeText(
                    context,
                    if (ok) "装配完成" else "装配失败，请查看运行日志（环境页）",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        onDismiss = { showReassemble = false },
    )
}

/** 插件清单数据（id / 标题 / 定位说明 / 来源）。 */
internal data class PluginEntry(val id: String, val title: String, val desc: String, val source: String, val autoInstall: Boolean = true)

internal val PLUGIN_LIST = listOf(
    PluginEntry(
        "dsh-mobile-nav",
        "dsh-mobile-nav（主插件）",
        "WebUI 移动端适配：导航、布局与触控优化，手机上可直接使用 WebUI。装配其余插件的前置。",
        "内置 · dsh-external/dsh-mobile-nav",
    ),
    PluginEntry(
        "dsh-web-ui-all",
        "dsh-web-ui-all",
        "WebUI 综合适配包：整合移动端样式与交互增强，覆盖 WebUI 的常用页面。",
        "npm · @linxin666/dsh-web-ui-all",
    ),
    PluginEntry(
        "dshmarket",
        "dshmarket（插件市场）",
        "WebUI 内的插件市场：浏览与安装社区插件，支持搜索与版本管理。",
        "npm · dshmarket",
    ),
    PluginEntry(
        "dsh-usage-stats",
        "dsh-usage-stats",
        "用量统计：会话与 Token 用量的可视化统计面板。",
        "npm · dsh-usage-stats",
    ),
    PluginEntry(
        "dsh-genui",
        "dsh-genui",
        "通用 UI 增强：WebUI 的界面细节打磨（npm 发布版，含构建产物）。",
        "npm · @changfenhuang/dsh-genui",
    ),
    PluginEntry(
        "dsh-infinite-gen-4",
        "dsh-infinite-gen-4（无限红队）",
        "DeepSeek v4.1 无限红队工具包。源码仓库未发布构建产物，暂不支持自动装配，可在 dshmarket 手动处理。",
        "GitHub · Minglink/dsh-infinite-gen-4",
        autoInstall = false,
    ),
    PluginEntry(
        "dsh-purge",
        "dsh-purge（破甲）",
        "破甲提示词包。源码仓库未发布构建产物，暂不支持自动装配，可在 dshmarket 手动处理。",
        "GitHub · YuJunZhiXue/dsh-purge",
        autoInstall = false,
    ),
)

/** 插件清单访问（AddonManager 的 marker 状态包装）。 */
internal object AddonManagerCompat {
    val pluginDescriptions: List<PluginEntry> get() = PLUGIN_LIST
    val autoInstallable: List<PluginEntry> get() = PLUGIN_LIST.filter { it.autoInstall }

    fun isInstalled(id: String): Boolean = if (id == "dsh-mobile-nav") {
        AddonManager.isInstalled()
    } else {
        AddonManager.isCompatInstalled(id)
    }

    fun isFailed(id: String): Boolean = AddonManager.isFailed(id)

    fun installedIds(): Set<String> =
        autoInstallable.map { it.id }.filter { isInstalled(it) }.toSet()
}

@Composable
private fun PluginCard(entry: PluginEntry, installed: Boolean, failed: Boolean) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        BasicComponent(
            title = entry.title,
            summary = entry.desc,
            endActions = {
                if (entry.autoInstall) {
                    StatusBadge(installed, failed)
                } else {
                    NotAvailableBadge()
                }
            },
        )
        Text(
            text = "来源 · ${entry.source}",
            fontSize = 11.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun NotAvailableBadge() {
    val dark = ThemeStore.isDark()
    Text(
        text = "暂不支持",
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = Color(0xFF8A6D1F),
        modifier = Modifier
            .background(if (dark) Color(0xFF3E351B) else Color(0xFFFFF6D9), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun InstallProgressCard(p: InstallProgress) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        BasicComponent(
            title = "正在装配 ${p.id}",
            summary = "${p.step} · ${p.index}/${p.total}（下载 → 装配 → 临时端口验证）",
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = "装配进度",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
        )
    }
}

@Composable
private fun StatusBadge(installed: Boolean, failed: Boolean = false) {
    val dark = ThemeStore.isDark()
    val bgColor = when {
        installed && dark -> Color(0xFF1A3825)
        installed -> Color(0xFFDFFAE4)
        failed && dark -> Color(0xFF3A1B1B)
        failed -> Color(0xFFFDE4E4)
        dark -> Color(0xFF3E2F1B)
        else -> Color(0xFFFFF0DB)
    }
    val fgColor = when {
        installed && dark -> Color(0xFF36D167)
        installed -> Color(0xFF2E7D32)
        failed && dark -> Color(0xFFFF6B6B)
        failed -> Color(0xFFC62828)
        dark -> Color(0xFFF5A623)
        else -> Color(0xFFB45F00)
    }
    Text(
        text = when {
            installed -> "已装配"
            failed -> "装配失败"
            else -> "待装配"
        },
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = fgColor,
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** miuix 卡片分节标题。 */
@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
    )
}
