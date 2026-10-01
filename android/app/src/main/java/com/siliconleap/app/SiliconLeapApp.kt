package com.siliconleap.app

import android.app.Application
import com.siliconleap.app.runtime.AddonManager
import com.siliconleap.app.runtime.AppSettings
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.SubsystemManager

class SiliconLeapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeManager.attach(applicationContext)
        SubsystemManager.attach(applicationContext)
        AddonManager.attach(applicationContext)
        // 尽早拉起服务：运行时已装 + 自动启动开启时，在 Activity/Compose
        // 初始化之前就开始启动 node 服务（冷启动为 WebUI 可达的主要耗时，越早越好）。
        // 分区选择引导已取消（默认容器分区），首次启动（运行时未装）不触发，
        // 保持「环境页拉取并安装运行时」的设计；
        // App.kt 的 LaunchedEffect 触发受 bootstrap() 的进行中状态防重入保护，不会重复。
        if (AppSettings.runtimeInstalled(applicationContext) &&
            AppSettings.autoStartService(applicationContext)
        ) {
            RuntimeManager.bootstrap()
        }
    }
}
