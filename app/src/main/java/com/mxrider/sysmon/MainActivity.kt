package com.mxrider.sysmon

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mxrider.sysmon.ui.SysMonRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SysMonRoot()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode is in configChanges, so a runtime light/dark flip lands here
        // without recreation; re-evaluate the system-bar icon contrast that
        // enableEdgeToEdge decided once in onCreate.
        enableEdgeToEdge()
    }
}
