package com.mxrider.sysmon

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mxrider.sysmon.core.TabDestination
import com.mxrider.sysmon.ui.SysMonRoot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel

/**
 * "Open this tab" requests carried by Intent extras (the readout
 * notifications' tap). MainScaffold consumes them; a plain channel keeps
 * this free of any navigation library.
 *
 * A conflated [Channel], deliberately not a StateFlow: StateFlow's equality
 * conflation swallows a request identical to one left unconsumed, and a
 * single wedged PERFORMANCE value would turn every later notification tap
 * into a silent no-op (observed on a real device). The channel
 * delivers every send, and still buffers the latest request across the gap
 * between onCreate/onNewIntent and the scaffold collector attaching.
 *
 * The channel itself lives on the activity instance, not here: a channel
 * delivers each send to exactly ONE receiver, so a process-global mailbox
 * could be drained by a stale composition's collector instead of the live
 * one. Per-instance ownership makes that impossible.
 */
internal object TabIntents {
    const val EXTRA_OPEN_TAB = "com.mxrider.sysmon.extra.OPEN_TAB"

    fun parse(intent: Intent?): TabDestination? {
        val name = intent?.getStringExtra(EXTRA_OPEN_TAB) ?: return null
        return runCatching { TabDestination.valueOf(name) }.getOrNull()
    }

    fun offer(intent: Intent?, into: SendChannel<TabDestination>) {
        parse(intent)?.let(into::trySend)
    }
}

class MainActivity : ComponentActivity() {
    private val tabRequests = Channel<TabDestination>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The launch intent's tab seeds the FIRST composition instead of going
        // through the channel: a channel request lands one frame after the
        // scaffold composes the start page, so every cold notification tap
        // flashed the wrong screen before jumping. Fresh launches only — after
        // process death the system re-delivers the original intent, and the
        // restored tab (rememberSaveable) must win over the stale extra.
        val initialTab = if (savedInstanceState == null) TabIntents.parse(intent) else null
        setContent {
            SysMonRoot(tabRequests, initialTab)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        TabIntents.offer(intent, tabRequests)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode is in configChanges, so a runtime light/dark flip lands here
        // without recreation; re-evaluate the system-bar icon contrast that
        // enableEdgeToEdge decided once in onCreate.
        enableEdgeToEdge()
    }
}
