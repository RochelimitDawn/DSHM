package com.siliconleap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.siliconleap.app.ui.SiliconLeapApp
import com.siliconleap.app.ui.theme.SiliconLeapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SiliconLeapTheme {
                SiliconLeapApp()
            }
        }
    }
}
