package com.mxrider.sysmon.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.MainActivity
import com.mxrider.sysmon.R
import com.mxrider.sysmon.TabIntents
import com.mxrider.sysmon.core.CpuSource
import com.mxrider.sysmon.core.Format
import com.mxrider.sysmon.core.TabDestination
import com.mxrider.sysmon.core.averageCurFreqKhz
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * User-enabled foreground service keeping a silent ongoing notification with
 * the live CPU readout ("CPU ~43% · 1.03 GHz"). The ~ marks the frequency
 * estimate — the same honesty rule as the Performance tab.
 *
 * Lifecycle follows the "CPU usage in notification" setting as the single
 * source of truth: MainScaffold starts the service whenever the setting is on
 * (which is also how the notification returns at the next app open after a
 * reboot — deliberately no BOOT_COMPLETED receiver), and the service stops
 * itself the moment the setting turns off. Sampling pauses while the screen
 * is off: nobody can see the readout then, so updating it would betray the
 * battery caption that sold the toggle.
 */
class CpuNotificationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenOn = MutableStateFlow(true)
    private var running = false
    private var lastTitle: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> screenOn.value = true
                Intent.ACTION_SCREEN_OFF -> screenOn.value = false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        screenOn.value = getSystemService(PowerManager::class.java)?.isInteractive != false
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Enter the foreground within the grace window, before the first
        // sample lands: a placeholder readout beats an ANR.
        startInForeground(buildNotification(getString(R.string.cpu_notif_title, DASH), null))
        if (!running) {
            running = true
            scope.launch {
                // Flipping the setting off stops the service no matter who
                // started it — there is no other stop path.
                Graph.settings.settings.collectLatest { settings ->
                    if (!settings.cpuNotification) stopCompletely()
                }
            }
            scope.launch {
                screenOn.collectLatest { on ->
                    if (on) {
                        while (true) {
                            update()
                            delay(UPDATE_INTERVAL_MS)
                        }
                    }
                }
            }
        }
        // The user explicitly asked for a persistent readout; if the system
        // reaps the service under memory pressure, come back when it can.
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
            // Some OEM teardown paths deliver onDestroy twice.
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopCompletely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun update() {
        val snapshot = try {
            Graph.cpu.sample()
        } catch (_: Exception) {
            null
        }
        val title: String
        val text: String?
        if (snapshot == null || snapshot.source == CpuSource.UNAVAILABLE) {
            title = getString(R.string.cpu_notif_title, DASH)
            text = getString(R.string.cpu_notif_unavailable)
        } else {
            val prefix = if (snapshot.source == CpuSource.FREQ_ESTIMATE) "~" else ""
            val percent = prefix + Format.percent(snapshot.utilization)
            val freqKhz = snapshot.averageCurFreqKhz()
            title = if (freqKhz > 0) {
                getString(R.string.cpu_notif_title_freq, percent, Format.freqGhz(freqKhz))
            } else {
                getString(R.string.cpu_notif_title, percent)
            }
            text = if (snapshot.source == CpuSource.FREQ_ESTIMATE) {
                getString(R.string.cpu_notif_text_estimate)
            } else {
                getString(R.string.cpu_notif_text)
            }
        }
        if (title != lastTitle) {
            lastTitle = title
            postUpdate(buildNotification(title, text))
        }
    }

    private fun postUpdate(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Revoked mid-run (which normally also kills the process); the
            // setting is reconciled to OFF at the next app open.
            return
        }
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, text: String?): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(TabIntents.EXTRA_OPEN_TAB, TabDestination.PERFORMANCE.name)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cpu)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // 31+ otherwise delays FGS notifications ~10s; a readout the
            // user just toggled on has to appear immediately.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.cpu_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW, // silent, never peeks
            ).apply {
                description = getString(R.string.cpu_notif_channel_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "cpu_readout"
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 2_000L

        /** Same placeholder convention as the panels: never a fabricated value. */
        private const val DASH = "—"

        /** True when notifications can actually be shown (pre-33: always). */
        fun canPostNotifications(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CpuNotificationService::class.java))
        }
    }
}
