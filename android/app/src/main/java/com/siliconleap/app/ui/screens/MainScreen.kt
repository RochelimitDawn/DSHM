package com.siliconleap.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siliconleap.app.runtime.RuntimeState
import com.siliconleap.app.ui.component.FloatingBottomBar
import com.siliconleap.app.ui.component.FloatingBottomBarItem
import com.siliconleap.app.ui.component.rememberBlurBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val PAGE_COUNT = 3

private enum class MainDestination(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Rounded.Cottage),
    Runtime("环境", Icons.Rounded.FileDownload),
    Settings("设置", Icons.Rounded.Settings),
}

/** 主界面：HorizontalPager + KSU 悬浮液态玻璃底栏（默认开启）。 */
@Composable
fun MainScreen(state: RuntimeState) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { PAGE_COUNT })
    val blurBackdrop = rememberBlurBackdrop(enableBlur = true)
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val bottomBar = @Composable {
        Box(Modifier.fillMaxWidth()) {
            FloatingBottomBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                selectedIndex = { pagerState.currentPage },
                onSelected = { index ->
                    if (index != pagerState.currentPage) {
                        scope.launch { pagerState.scrollToPage(index) }
                    }
                },
                backdrop = backdrop,
                tabsCount = MainDestination.entries.size,
                isBlurEnabled = blurBackdrop != null,
            ) {
                MainDestination.entries.forEachIndexed { index, destination ->
                    FloatingBottomBarItem(
                        onClick = {
                            if (index != pagerState.currentPage) {
                                scope.launch { pagerState.scrollToPage(index) }
                            }
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                    ) {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = destination.label,
                        )
                        Text(
                            text = destination.label,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                        )
                    }
                }
            }
        }
    }

    Scaffold(bottomBar = bottomBar) { padding ->
        Box(Modifier.fillMaxSize().consumeWindowInsets(padding)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.layerBackdrop(backdrop),
                overscrollEffect = null,
                // 预组合相邻页：切页时目标页已就绪，瞬时切换（scrollToPage）零等待零卡顿。
                // 页面用 isActive 控制轮询，非激活页不更新 UI，避免 backdrop 层被高频重录。
                beyondViewportPageCount = 1,
            ) { page ->
                val bottomInnerPadding = padding.calculateBottomPadding()
                // 仅当前页激活轮询：非激活页 produceState 循环空转（不更新 state，不触发重组/backdrop 重录）
                val isActive = pagerState.currentPage == page
                when (page) {
                    0 -> HomeScreen(state, bottomInnerPadding, isActive = isActive) {
                        if (1 != pagerState.currentPage) {
                            scope.launch { pagerState.scrollToPage(1) }
                        }
                    }
                    1 -> RuntimeScreen(state, bottomInnerPadding, isActive = isActive)
                    2 -> SettingsScreen(state, bottomInnerPadding, isActive = isActive)
                }
            }
        }
    }
}
