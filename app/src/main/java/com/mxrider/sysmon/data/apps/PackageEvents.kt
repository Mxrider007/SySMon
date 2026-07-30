package com.mxrider.sysmon.data.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide install/uninstall/update signal. The app-list ViewModels are
 * activity-scoped and outlive their screens, so before this existed a package
 * change only surfaced through each screen's 60-second staleness window — an
 * uninstalled app could sit in a visible table for a minute claiming to exist.
 *
 * Registered once from [com.mxrider.sysmon.Graph] on the application context
 * and never unregistered: package churn is process-lifetime interest, and the
 * receiver holds no activity reference to leak. PACKAGE_* broadcasts remain
 * deliverable to context-registered receivers on modern Android (they are
 * exempt from the implicit-broadcast restrictions).
 */
object PackageEvents {

    private val _generation = MutableStateFlow(0L)

    /** Bumps on every package add/remove/replace; collectors reload on change. */
    val generation: StateFlow<Long> = _generation.asStateFlow()

    /**
     * Synchronous per-package listeners (icon-cache eviction). Registered from
     * the composition root — this data-layer object stays ignorant of the UI.
     */
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // Explicit export flag and a guard, matching CpuNotificationService's
        // registration. All four actions are protected broadcasts, so the
        // API-34 flag requirement does not bite today — but this runs inside
        // Application.onCreate, where an unguarded throw is an unrecoverable
        // launch crash that would also leave the rest of Graph uninitialised.
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        // REPLACED arrives as REMOVED+ADDED too; double bumps
                        // are harmless (collectors conflate) and listeners are
                        // idempotent.
                        intent.data?.schemeSpecificPart?.let { pkg ->
                            listeners.forEach { it(pkg) }
                        }
                        _generation.value += 1
                    }
                },
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }
}
