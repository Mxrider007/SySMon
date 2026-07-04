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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
import com.mxrider.sysmon.core.LogSample
import com.mxrider.sysmon.core.SysMonSettings
import com.mxrider.sysmon.core.TabDestination
import com.mxrider.sysmon.core.averageCurFreqKhz
import com.mxrider.sysmon.data.system.DefaultNetworkMonitor
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * User-enabled foreground service for everything that monitors while the app
 * is away: the silent ongoing readout notifications — live CPU
 * ("CPU ~43% · 1.03 GHz", optionally with a rendered percentage tile as its
 * large icon), network speed ("Net ↓ 2.1 Mbps · ↑ 340 Kbps") and battery
 * ("Battery 84% · -1.8 W") — plus the on-device performance log (CSV rows of
 * all of the above). The ~ marks the frequency estimate — the same honesty
 * rule as the Performance tab; network rates come from on-device
 * TrafficStats counters, never from the network itself.
 *
 * Lifecycle follows the monitoring settings as the single source of truth:
 * MainScaffold starts the service whenever any readout or logging is on
 * (which is also how monitoring returns at the next app open after a reboot —
 * deliberately no BOOT_COMPLETED receiver), and the service stops itself the
 * moment everything is off. The first enabled readout anchors the foreground
 * state (CPU preferred); with logging on and no readout, a small static
 * "logging" notification anchors instead — an invisible foreground sampler
 * would not be honest. Readout sampling pauses while the screen is off
 * (nobody can see them, so updating would betray the battery caption that
 * sold the toggles); the LOG loop deliberately keeps running — recording
 * what happens while the screen is off is its whole point, and its Settings
 * caption says so.
 */
class CpuNotificationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenOn = MutableStateFlow(true)
    private var running = false
    private var lastCpuTitle: String? = null
    private var lastNetTitle: String? = null
    private var lastBatKey: String? = null
    private var anchorId = 0

    @Volatile
    private var current: SysMonSettings? = null

    // Private instance, not Graph.network: the monitor is stateful (previous
    // counters + timestamp), and sharing it with the UI sampler would slice
    // both callers' rate windows into interleaved fragments.
    private val netMonitor by lazy { DefaultNetworkMonitor(this) }

    // The log loop runs on its own cadence, so it needs its own rate window
    // too — sharing either of the other instances would corrupt both.
    private val logNetMonitor by lazy { DefaultNetworkMonitor(this) }

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
        createChannels()
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
        // sample or settings emission lands: a placeholder readout beats an
        // ANR. The settings collector re-anchors within milliseconds if the
        // CPU readout turns out to be the disabled one.
        startInForeground(CPU_NOTIFICATION_ID, placeholderNotification(CPU_NOTIFICATION_ID))
        if (!running) {
            running = true
            scope.launch {
                // Flipping the last enabled setting off stops the service no
                // matter who started it — there is no other stop path.
                Graph.settings.settings.collectLatest { settings ->
                    applySettings(settings)
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
            scope.launch {
                // Not gated on screenOn — see the class doc. collectLatest
                // restarts the loop on an interval change and cancels it the
                // moment logging turns off.
                Graph.settings.settings
                    .map { it.logging to it.logInterval }
                    .distinctUntilChanged()
                    .collectLatest { (enabled, interval) ->
                        if (!enabled) return@collectLatest
                        while (true) {
                            if (!logOnce()) break
                            delay(interval.intervalMs)
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

    /** Reconciles anchor, visible notifications, and repaint guards. */
    private suspend fun applySettings(settings: SysMonSettings) {
        val previous = current
        current = settings
        if (!settings.cpuNotification && !settings.netNotification &&
            !settings.batteryNotification && !settings.logging
        ) {
            stopCompletely()
            return
        }
        val wantedAnchor = when {
            settings.cpuNotification -> CPU_NOTIFICATION_ID
            settings.netNotification -> NET_NOTIFICATION_ID
            settings.batteryNotification -> BAT_NOTIFICATION_ID
            else -> LOG_NOTIFICATION_ID // logging is the only thing left on
        }
        if (wantedAnchor != anchorId) {
            startInForeground(wantedAnchor, placeholderNotification(wantedAnchor))
            // The placeholder just overwrote a live readout; force reposts.
            lastCpuTitle = null
            lastNetTitle = null
            lastBatKey = null
        }
        // startForeground only manages its own id; a readout that just got
        // disabled has to be cancelled explicitly. The logging notification
        // exists only as anchor-of-last-resort, so it also goes whenever any
        // readout holds the anchor.
        val manager = getSystemService(NotificationManager::class.java)
        if (!settings.cpuNotification) manager?.cancel(CPU_NOTIFICATION_ID)
        if (!settings.netNotification) manager?.cancel(NET_NOTIFICATION_ID)
        if (!settings.batteryNotification) manager?.cancel(BAT_NOTIFICATION_ID)
        if (anchorId != LOG_NOTIFICATION_ID) manager?.cancel(LOG_NOTIFICATION_ID)
        // Accent or box changes repaint the same title text; toggling a
        // readout back on must repost it even if its title never changed.
        if (previous?.accent != settings.accent ||
            previous?.cpuNotificationBox != settings.cpuNotificationBox ||
            previous?.cpuNotification != settings.cpuNotification
        ) {
            lastCpuTitle = null
        }
        if (previous?.netNotification != settings.netNotification) lastNetTitle = null
        if (previous?.batteryNotification != settings.batteryNotification) lastBatKey = null
        update()
    }

    private fun stopCompletely() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(CPU_NOTIFICATION_ID)
        manager?.cancel(NET_NOTIFICATION_ID)
        manager?.cancel(BAT_NOTIFICATION_ID)
        manager?.cancel(LOG_NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun update() {
        val settings = current ?: return
        if (settings.cpuNotification) updateCpu(settings)
        if (settings.netNotification) updateNet()
        if (settings.batteryNotification) updateBattery()
    }

    private suspend fun updateCpu(settings: SysMonSettings) {
        val snapshot = try {
            Graph.cpu.sample()
        } catch (_: Exception) {
            null
        }
        val title: String
        val text: String?
        var percent: String? = null
        if (snapshot == null || snapshot.source == CpuSource.UNAVAILABLE) {
            title = getString(R.string.cpu_notif_title, DASH)
            text = getString(R.string.cpu_notif_unavailable)
        } else {
            val prefix = if (snapshot.source == CpuSource.FREQ_ESTIMATE) "~" else ""
            percent = prefix + Format.percent(snapshot.utilization)
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
        if (title != lastCpuTitle) {
            lastCpuTitle = title
            val box = if (settings.cpuNotificationBox && percent != null && snapshot != null) {
                renderUsageBox(
                    percentText = percent,
                    perCore = snapshot.perCore.map { if (it.online) it.utilization else 0f },
                    accentArgb = settings.accent.controlLightArgb,
                )
            } else {
                null
            }
            postUpdate(
                CPU_NOTIFICATION_ID,
                buildReadoutNotification(CPU_CHANNEL_ID, R.drawable.ic_stat_cpu, title, text, box),
            )
        }
    }

    private suspend fun updateNet() {
        val snapshot = try {
            netMonitor.sample()
        } catch (_: Exception) {
            null
        }
        val title: String
        val text: String?
        // Zero totals after a sample means TrafficStats is unsupported here:
        // on any real device the boot process alone moves bytes.
        if (snapshot == null || (snapshot.totalRxBytes == 0L && snapshot.totalTxBytes == 0L)) {
            title = getString(R.string.net_notif_title, DASH, DASH)
            text = getString(R.string.net_notif_unavailable)
        } else {
            title = getString(
                R.string.net_notif_title,
                Format.bitsPerSec(snapshot.rxBytesPerSec),
                Format.bitsPerSec(snapshot.txBytesPerSec),
            )
            val name = snapshot.networkName
            text = if (name != null) {
                getString(R.string.net_notif_text_on, name)
            } else {
                getString(R.string.cpu_notif_text)
            }
        }
        if (title != lastNetTitle) {
            lastNetTitle = title
            postUpdate(
                NET_NOTIFICATION_ID,
                buildReadoutNotification(NET_CHANNEL_ID, R.drawable.ic_stat_net, title, text, null),
            )
        }
    }

    private suspend fun updateBattery() {
        val snapshot = try {
            Graph.battery.sample()
        } catch (_: Exception) {
            null
        }
        val title: String
        val text: String?
        if (snapshot == null) {
            title = getString(R.string.bat_notif_title, DASH)
            text = getString(R.string.bat_notif_unavailable)
        } else {
            // One power-ish number in the title: watts preferred, milliamps
            // when only current is reported, level alone otherwise — the
            // same signed convention as the Battery panel (negative =
            // discharging), never fabricated.
            val level = "${snapshot.levelPercent}%"
            val draw = snapshot.powerWatts?.let { w ->
                if (w >= 0) "+${Format.watts(w)}" else Format.watts(w)
            } ?: snapshot.currentMicroA?.let {
                String.format(Locale.US, "%+d mA", it / 1000)
            }
            title = if (draw != null) {
                getString(R.string.bat_notif_title_power, level, draw)
            } else {
                getString(R.string.bat_notif_title, level)
            }
            val state = buildString {
                append(snapshot.status)
                snapshot.plugType?.let { append(" ($it)") }
                snapshot.temperatureC?.let { append(" · ").append(Format.celsius(it)) }
            }
            text = getString(R.string.bat_notif_text_on, state)
        }
        // Temperature moves without the title changing, so the battery guard
        // covers both lines (the others only need their title).
        val key = "$title|$text"
        if (key != lastBatKey) {
            lastBatKey = key
            postUpdate(
                BAT_NOTIFICATION_ID,
                buildReadoutNotification(BAT_CHANNEL_ID, R.drawable.ic_stat_battery, title, text, null),
            )
        }
    }

    /** One log row; false ends the loop (file full or unwritable). */
    private suspend fun logOnce(): Boolean {
        val sample = LogSample(
            timeMillis = System.currentTimeMillis(),
            cpu = runCatching { Graph.cpu.sample() }.getOrNull(),
            memory = runCatching { Graph.memory.sample() }.getOrNull(),
            battery = runCatching { Graph.battery.sample() }.getOrNull(),
            network = runCatching { logNetMonitor.sample() }.getOrNull(),
            screenOn = screenOn.value,
        )
        if (Graph.perfLogger.append(sample)) return true
        // Flip the setting so the Settings toggle tells the truth; the
        // settings collector then reconciles the service (and stops it when
        // nothing else is on).
        Graph.settings.setLogging(false)
        return false
    }

    private fun postUpdate(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Revoked mid-run (which normally also kills the process); the
            // settings are reconciled to OFF at the next app open.
            return
        }
        getSystemService(NotificationManager::class.java)
            ?.notify(id, notification)
    }

    private fun startInForeground(id: Int, notification: Notification) {
        anchorId = id
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }

    private fun placeholderNotification(id: Int): Notification = when (id) {
        NET_NOTIFICATION_ID -> buildReadoutNotification(
            NET_CHANNEL_ID,
            R.drawable.ic_stat_net,
            getString(R.string.net_notif_title, DASH, DASH),
            null,
            null,
        )

        BAT_NOTIFICATION_ID -> buildReadoutNotification(
            BAT_CHANNEL_ID,
            R.drawable.ic_stat_battery,
            getString(R.string.bat_notif_title, DASH),
            null,
            null,
        )

        // Static by design: a live "log size" counter would turn the anchor
        // of last resort into a fourth readout nobody asked for.
        LOG_NOTIFICATION_ID -> buildReadoutNotification(
            LOG_CHANNEL_ID,
            R.drawable.ic_stat_log,
            getString(R.string.log_notif_title),
            getString(R.string.log_notif_text),
            null,
            destination = TabDestination.SETTINGS,
        )

        else -> buildReadoutNotification(
            CPU_CHANNEL_ID,
            R.drawable.ic_stat_cpu,
            getString(R.string.cpu_notif_title, DASH),
            null,
            null,
        )
    }

    private fun buildReadoutNotification(
        channelId: String,
        smallIcon: Int,
        title: String,
        text: String?,
        largeIcon: Bitmap?,
        destination: TabDestination = TabDestination.PERFORMANCE,
    ): Notification {
        val tap = PendingIntent.getActivity(
            this,
            // Distinct request code per destination: extras don't count for
            // PendingIntent identity, so sharing one code would silently
            // rewrite every notification's target to the last one built.
            destination.ordinal,
            Intent(this, MainActivity::class.java)
                .putExtra(TabIntents.EXTRA_OPEN_TAB, destination.name)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .apply { if (largeIcon != null) setLargeIcon(largeIcon) }
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

    /**
     * The "usage box": an accent-colored rounded tile with the same percent
     * string as the title (tilde included — the estimate marker survives) over
     * faint per-core bars. Rendered, not a layout, so it can be the large icon.
     */
    private fun renderUsageBox(
        percentText: String,
        perCore: List<Float>,
        accentArgb: Long,
    ): Bitmap {
        val size = BOX_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = accentArgb.toInt()
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 40f, 40f, paint)
        if (perCore.isNotEmpty()) {
            paint.color = 0x4DFFFFFF // 30% white
            val gap = 8f
            val barWidth = (size - gap * (perCore.size + 1)) / perCore.size
            perCore.forEachIndexed { index, utilization ->
                val height = (size - 32f) * utilization.coerceIn(0f, 1f)
                val left = gap + index * (barWidth + gap)
                // Clip inside the rounded corners rather than clipping the
                // bars to a path: cheaper and invisible at icon size.
                canvas.drawRect(left, size - 8f - height, left + barWidth, size - 8f, paint)
            }
        }
        paint.color = Color.WHITE
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 84f
        val maxWidth = size - 24f
        val measured = paint.measureText(percentText)
        if (measured > maxWidth) paint.textSize = 84f * maxWidth / measured
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(percentText, size / 2f, baseline, paint)
        return bitmap
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CPU_CHANNEL_ID,
                getString(R.string.cpu_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW, // silent, never peeks
            ).apply {
                description = getString(R.string.cpu_notif_channel_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NET_CHANNEL_ID,
                getString(R.string.net_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.net_notif_channel_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                BAT_CHANNEL_ID,
                getString(R.string.bat_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.bat_notif_channel_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                LOG_CHANNEL_ID,
                getString(R.string.log_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.log_notif_channel_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CPU_CHANNEL_ID = "cpu_readout"
        private const val NET_CHANNEL_ID = "net_readout"
        private const val BAT_CHANNEL_ID = "battery_readout"
        private const val LOG_CHANNEL_ID = "logging_status"
        private const val CPU_NOTIFICATION_ID = 1
        private const val NET_NOTIFICATION_ID = 2
        private const val BAT_NOTIFICATION_ID = 3
        private const val LOG_NOTIFICATION_ID = 4
        private const val UPDATE_INTERVAL_MS = 2_000L
        private const val BOX_SIZE_PX = 256

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
