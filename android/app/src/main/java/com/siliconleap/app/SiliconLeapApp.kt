package com.siliconleap.app

import android.app.Application
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.SubsystemManager

class SiliconLeapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeManager.attach(applicationContext)
        SubsystemManager.attach(applicationContext)
    }
}
