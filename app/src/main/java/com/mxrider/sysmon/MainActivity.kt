package com.mxrider.sysmon

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mxrider.sysmon.core.TabDestination
import com.mxrider.sysmon.ui.SysMonRoot
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Mailbox for "open this tab" requests carried by Intent extras (the CPU
 * notification's tap). MainScaffold consumes and clears it; a plain state
 * flow keeps this free of any navigation library.
 */
internal object TabIntents {
    const val EXTRA_OPEN_TAB = "com.mxrider.sysmon.extra.OPEN_TAB"

    val pending = MutableStateFlow<TabDestination?>(null)

    fun offer(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_OPEN_TAB) ?: return
        val tab = runCatching { TabDestination.valueOf(name) }.getOrNull() ?: return
        pending.value = tab
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Route the extra only on a genuinely fresh launch: after process
        // death the system re-delivers the original intent, and the restored
        // tab (rememberSaveable) must win over a stale notification extra.
        if (savedInstanceState == null) TabIntents.offer(intent)
        setContent {
            SysMonRoot()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        TabIntents.offer(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode is in configChanges, so a runtime light/dark flip lands here
        // without recreation; re-evaluate the system-bar icon contrast that
        // enableEdgeToEdge decided once in onCreate.
        enableEdgeToEdge()
    }
}
