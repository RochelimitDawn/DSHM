package com.siliconleap.app

import android.app.Application
import com.siliconleap.app.runtime.RuntimeManager

class SiliconLeapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeManager.attach(applicationContext)
    }
}
