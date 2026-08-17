package com.siliconleap.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Security
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siliconleap.app.runtime.AppSettings
import com.siliconleap.app.runtime.RootManager
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.ui.component.rememberBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 首次启动引导：选择运行分区。
 * - Root 分区：检测设备 root 并请求授权（Magisk/KernelSU 弹窗），授权后以真 root 宿主执行。
 * - 容器分区（非 root）：proot 容器（Termux 运行时 + Debian 子系统），自动安装。
 */
@Composable
fun SetupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val backdrop = rememberBlurBackdrop(enableBlur = true)

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun chooseRoot() {
        if (busy) return
        busy = true
        scope.launch {
            val hasSu = withContext(Dispatchers.IO) { RootManager.rootAvailable() }
            if (!hasSu) {
                AppSettings.setRootShellEnabled(context, false)
                RuntimeManager.setRunMode(context, AppSettings.RUN_MODE_CONTAINER)
                toast("未检测到 root，将使用容器分区")
                busy = false
                return@launch
            }
            val ok = withContext(Dispatchers.IO) { RootManager.requestRoot() }
            if (ok) {
                AppSettings.setRootShellEnabled(context, true)
                RuntimeManager.setRunMode(context, AppSettings.RUN_MODE_ROOT)
                toast("已获得 root 权限，将使用 Root 分区")
            } else {
                RootManager.clearGrant()
                AppSettings.setRootShellEnabled(context, false)
                RuntimeManager.setRunMode(context, AppSettings.RUN_MODE_CONTAINER)
                toast("未获得 root 授权，将使用容器分区")
            }
            busy = false
        }
    }

    fun chooseContainer() {
        if (busy) return
        AppSettings.setRootShellEnabled(context, false)
        RuntimeManager.setRunMode(context, AppSettings.RUN_MODE_CONTAINER)
        toast("将使用容器分区（proot）")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            .padding(
                WindowInsets.systemBars.add(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal)
                    .asPaddingValues(),
            )
            .padding(horizontal = 20.dp, vertical = 32.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "选择运行分区",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "DSHM 通过分区执行 agent 命令。首次使用请选择一种方式，之后可在设置中切换。",
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(32.dp))

        // Root 分区
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { chooseRoot() },
            showIndication = !busy,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = "Root 分区",
                        modifier = Modifier.size(30.dp),
                        tint = colorScheme.primary,
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Root 分区",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "以真 root 在宿主 Android 执行。需设备已 root（Magisk/KernelSU），首次选择会弹出授权框。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 容器分区
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { chooseContainer() },
            showIndication = !busy,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Storage,
                        contentDescription = "容器分区",
                        modifier = Modifier.size(30.dp),
                        tint = colorScheme.primary,
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "容器分区（非 root）",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "自动安装 Termux 运行时与 Debian 子系统（proot 免 root）。无需 root，兼容所有设备。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (busy) "正在检测并请求授权…" else "",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
