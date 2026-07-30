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
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.MainActivity
import com.mxrider.sysmon.R
import com.mxrider.sysmon.TabIntents
import com.mxrider.sysmon.core.BatterySnapshot
import com.mxrider.sysmon.core.CpuSnapshot
import com.mxrider.sysmon.core.CpuSource
import com.mxrider.sysmon.core.Format
import com.mxrider.sysmon.core.LogSample
import com.mxrider.sysmon.core.MemorySnapshot
import com.mxrider.sysmon.core.NetworkSnapshot
import com.mxrider.sysmon.core.SysMonSettings
import com.mxrider.sysmon.core.TabDestination
import com.mxrider.sysmon.core.averageCurFreqKhz
import com.mxrider.sysmon.data.system.DefaultCpuMonitor
import com.mxrider.sysmon.data.system.DefaultNetworkMonitor
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * User-enabled foreground service for everything that monitors while the app
 * is away: the silent ongoing readout notifications — live CPU
 * ("CPU ~43% · 1.03 GHz"), network speed ("Net ↓ 2.1 Mbps · ↑ 340 Kbps"),
 * battery ("Battery 84% · -1.8 W") and memory ("Memory 63% · 3.6 / 5.6 GB")
 * — plus the draggable floating mini-monitor card (CPU/MEM/NET/BAT, needs
 * "Display over other apps") and the on-device performance log (CSV rows of
 * all of the above). Each
 * readout optionally carries a rendered usage tile as its large icon, and its
 * status-bar icon is the live value itself, repainted every tick (a white
 * box with the number punched out; the net readout stacks its two rates).
 * The ~ marks the frequency estimate — the same honesty rule as the
 * Performance tab; network rates come from on-device TrafficStats counters,
 * never from the network itself.
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
    private var lastMemTitle: String? = null
    private var anchorId = 0

    // Serializes applySettings against the update loop's iterations. Without
    // it, a toggle-off's cancel() can interleave with an in-flight iteration
    // whose repaint guard was just reset: the iteration re-posts the readout
    // AFTER the cancel and the notification is orphaned in the shade forever
    // (the sampler never touches that readout again). Holding the lock across
    // a sample is milliseconds against a 2 s cadence.
    private val stateMutex = Mutex()

    @Volatile
    private var current: SysMonSettings? = null

    // Private instance, not Graph.network: the monitor is stateful (previous
    // counters + timestamp), and sharing it with the UI sampler would slice
    // both callers' rate windows into interleaved fragments.
    private val netMonitor by lazy { DefaultNetworkMonitor(this) }

    // The log loop runs on its own cadence, so it needs its own rate window
    // too — sharing either of the other instances would corrupt both.
    private val logNetMonitor by lazy { DefaultNetworkMonitor(this) }

    // Same isolation for CPU: /proc/stat utilization is a delta against the
    // previous read, so sharing Graph.cpu with the UI sampler would slice
    // every caller's window into interleaved fragments (hard 0 % on
    // sub-jiffy gaps). The freq-estimate path is stateless and immune, but
    // PROC_STAT ROMs are real and explicitly supported.
    private val cpuMonitor by lazy { DefaultCpuMonitor() }
    private val logCpuMonitor by lazy { DefaultCpuMonitor() }

    // Floating mini-monitor. Main-thread only (WindowManager contract); the
    // update loop hands it immutable strings via a thread-safe setter.
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var overlayView: OverlayMonitorView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

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
        // ANR. On repeat start commands (the UI calls start() on every
        // toggle flip) this must re-post the CURRENT anchor, not CPU's:
        // moving the anchor cancels the old anchor notification, and the
        // settings collector cannot repair that — an unchanged StateFlow
        // never re-emits, and a readout whose values are stable (a full
        // idle battery) is then held blank by its own repaint guard.
        val entryAnchor = if (anchorId != 0) anchorId else CPU_NOTIFICATION_ID
        startInForeground(entryAnchor, placeholderNotification(entryAnchor))
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
                            stateMutex.withLock { update() }
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
        } else {
            scope.launch {
                // The placeholder above just overwrote the live anchor
                // content; repaint everything enabled right away instead of
                // waiting out the repaint guards.
                stateMutex.withLock {
                    lastCpuTitle = null
                    lastNetTitle = null
                    lastBatKey = null
                    lastMemTitle = null
                    update()
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
        detachOverlay() // onDestroy runs on the main thread
        scope.cancel()
        super.onDestroy()
    }

    /** Reconciles anchor, visible notifications, overlay, and repaint guards. */
    private suspend fun applySettings(settings: SysMonSettings) = stateMutex.withLock {
        val previous = current
        current = settings
        if (!settings.cpuNotification && !settings.netNotification &&
            !settings.batteryNotification && !settings.memoryNotification &&
            !settings.overlayEnabled && !settings.logging
        ) {
            stopCompletely()
            return@withLock
        }
        val wantedAnchor = when {
            settings.cpuNotification -> CPU_NOTIFICATION_ID
            settings.netNotification -> NET_NOTIFICATION_ID
            settings.batteryNotification -> BAT_NOTIFICATION_ID
            settings.memoryNotification -> MEM_NOTIFICATION_ID
            // The overlay is visible by definition, so its anchor (like
            // LOG's) is a static last-resort notification, not a readout.
            settings.overlayEnabled -> OVERLAY_NOTIFICATION_ID
            else -> LOG_NOTIFICATION_ID // logging is the only thing left on
        }
        if (wantedAnchor != anchorId) {
            startInForeground(wantedAnchor, placeholderNotification(wantedAnchor))
            // The placeholder just overwrote a live readout; force reposts.
            lastCpuTitle = null
            lastNetTitle = null
            lastBatKey = null
            lastMemTitle = null
        }
        // startForeground only manages its own id; a readout that just got
        // disabled has to be cancelled explicitly. The logging notification
        // exists only as anchor-of-last-resort, so it also goes whenever any
        // readout holds the anchor.
        val manager = getSystemService(NotificationManager::class.java)
        if (!settings.cpuNotification) manager?.cancel(CPU_NOTIFICATION_ID)
        if (!settings.netNotification) manager?.cancel(NET_NOTIFICATION_ID)
        if (!settings.batteryNotification) manager?.cancel(BAT_NOTIFICATION_ID)
        if (!settings.memoryNotification) manager?.cancel(MEM_NOTIFICATION_ID)
        if (anchorId != LOG_NOTIFICATION_ID) manager?.cancel(LOG_NOTIFICATION_ID)
        if (anchorId != OVERLAY_NOTIFICATION_ID) manager?.cancel(OVERLAY_NOTIFICATION_ID)
        withContext(Dispatchers.Main) { syncOverlay(settings) }
        // Accent or box changes repaint the same title text; toggling a
        // readout back on must repost it even if its title never changed.
        val repaintAll = previous?.accent != settings.accent ||
            previous?.notificationBoxes != settings.notificationBoxes
        if (repaintAll || previous?.cpuNotification != settings.cpuNotification) {
            lastCpuTitle = null
        }
        if (repaintAll || previous?.netNotification != settings.netNotification) {
            lastNetTitle = null
        }
        if (repaintAll || previous?.batteryNotification != settings.batteryNotification) {
            lastBatKey = null
        }
        if (repaintAll || previous?.memoryNotification != settings.memoryNotification) {
            lastMemTitle = null
        }
        update()
    }

    private suspend fun stopCompletely() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(CPU_NOTIFICATION_ID)
        manager?.cancel(NET_NOTIFICATION_ID)
        manager?.cancel(BAT_NOTIFICATION_ID)
        manager?.cancel(MEM_NOTIFICATION_ID)
        manager?.cancel(LOG_NOTIFICATION_ID)
        manager?.cancel(OVERLAY_NOTIFICATION_ID)
        withContext(Dispatchers.Main) { detachOverlay() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Callers must hold [stateMutex]. Each source is sampled at most ONCE per
     * tick and the snapshot shared between its notification and the overlay —
     * sampling twice would slice the stateful monitors' delta windows.
     */
    private suspend fun update() {
        val settings = current ?: return
        // An external revoke of "Display over other apps" makes the system
        // tear the window down without telling us — the toggle would keep
        // claiming a monitor that no longer exists. Reconcile it here; the
        // settings write re-runs applySettings, which detaches and re-anchors.
        if (settings.overlayEnabled && !Settings.canDrawOverlays(this)) {
            scope.launch { Graph.settings.setOverlayEnabled(false) }
        }
        val overlay = settings.overlayEnabled
        val cpuSnap = if (settings.cpuNotification || overlay) {
            runCatching { cpuMonitor.sample() }.getOrNull()
        } else null
        val netSnap = if (settings.netNotification || overlay) {
            runCatching { netMonitor.sample() }.getOrNull()
        } else null
        val batSnap = if (settings.batteryNotification || overlay) {
            runCatching { Graph.battery.sample() }.getOrNull()
        } else null
        val memSnap = if (settings.memoryNotification || overlay) {
            runCatching { Graph.memory.sample() }.getOrNull()
        } else null
        if (settings.cpuNotification) updateCpu(settings, cpuSnap)
        if (settings.netNotification) updateNet(settings, netSnap)
        if (settings.batteryNotification) updateBattery(settings, batSnap)
        if (settings.memoryNotification) updateMemory(settings, memSnap)
        if (overlay) updateOverlay(settings, cpuSnap, memSnap, netSnap, batSnap)
    }

    private fun updateCpu(settings: SysMonSettings, snapshot: CpuSnapshot?) {
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
            val box = if (settings.notificationBoxes && percent != null && snapshot != null) {
                renderTile(
                    accentArgb = settings.accent.controlLightArgb,
                    lines = listOf(percent),
                    bars = snapshot.perCore.map { if (it.online) it.utilization else 0f },
                )
            } else {
                null
            }
            postUpdate(
                CPU_NOTIFICATION_ID,
                buildReadoutNotification(
                    CPU_CHANNEL_ID,
                    R.drawable.ic_stat_cpu,
                    title,
                    text,
                    box,
                    // The % is dropped from the punch-out — three glyphs is
                    // already the legibility limit — but the ~ honesty
                    // marker stays.
                    liveIcon = percent?.let { renderStatusValue(it.removeSuffix("%")) },
                ),
            )
        }
    }

    private fun updateNet(settings: SysMonSettings, snapshot: NetworkSnapshot?) {
        val title: String
        val text: String?
        // Compact down/up pair for the tile and the status-bar icon; null
        // while the counters are unreadable.
        var rates: Pair<String, String>? = null
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
            rates = compactBits(snapshot.rxBytesPerSec) to compactBits(snapshot.txBytesPerSec)
        }
        if (title != lastNetTitle) {
            lastNetTitle = title
            val box = if (settings.notificationBoxes && rates != null) {
                renderTile(
                    accentArgb = settings.accent.controlLightArgb,
                    lines = listOf("↓ ${rates.first}", "↑ ${rates.second}"),
                )
            } else {
                null
            }
            postUpdate(
                NET_NOTIFICATION_ID,
                buildReadoutNotification(
                    NET_CHANNEL_ID,
                    R.drawable.ic_stat_net,
                    title,
                    text,
                    box,
                    liveIcon = rates?.let { renderStatusRates(it.first, it.second) },
                ),
            )
        }
    }

    private fun updateBattery(settings: SysMonSettings, snapshot: BatterySnapshot?) {
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
            val box = if (settings.notificationBoxes && snapshot != null) {
                renderTile(
                    accentArgb = settings.accent.controlLightArgb,
                    lines = listOf("${snapshot.levelPercent}%"),
                    fill = snapshot.levelPercent / 100f,
                )
            } else {
                null
            }
            postUpdate(
                BAT_NOTIFICATION_ID,
                buildReadoutNotification(
                    BAT_CHANNEL_ID,
                    R.drawable.ic_stat_battery,
                    title,
                    text,
                    box,
                    liveIcon = snapshot?.let { renderStatusValue("${it.levelPercent}") },
                ),
            )
        }
    }

    private fun updateMemory(settings: SysMonSettings, snapshot: MemorySnapshot?) {
        val title: String
        val text: String?
        var percent: String? = null
        var usedFraction = 0f
        if (snapshot == null || snapshot.totalBytes <= 0) {
            title = getString(R.string.mem_notif_title, DASH)
            text = getString(R.string.mem_notif_unavailable)
        } else {
            usedFraction = (snapshot.usedBytes.toFloat() / snapshot.totalBytes).coerceIn(0f, 1f)
            percent = Format.percent(usedFraction)
            title = getString(
                R.string.mem_notif_title_used,
                percent,
                usedOfTotal(snapshot.usedBytes, snapshot.totalBytes),
            )
            text = getString(R.string.mem_notif_text_on, Format.bytes(snapshot.availBytes))
        }
        // Percent, the GB pair and available all move together, so the title
        // alone is guard enough (like CPU and net).
        if (title != lastMemTitle) {
            lastMemTitle = title
            val box = if (settings.notificationBoxes && percent != null) {
                renderTile(
                    accentArgb = settings.accent.controlLightArgb,
                    lines = listOf(percent),
                    fill = usedFraction,
                )
            } else {
                null
            }
            postUpdate(
                MEM_NOTIFICATION_ID,
                buildReadoutNotification(
                    MEM_CHANNEL_ID,
                    R.drawable.ic_stat_memory,
                    title,
                    text,
                    box,
                    liveIcon = percent?.let { renderStatusValue(it.removeSuffix("%")) },
                ),
            )
        }
    }

    /**
     * Pushes this tick's snapshots into the floating card. Same honesty rules
     * as the notifications: ~ marks the frequency estimate, dash for anything
     * unreadable; the battery temperature rides along because it is the
     * overlay's most-requested metric and BatteryManager hands it over anyway.
     */
    private fun updateOverlay(
        settings: SysMonSettings,
        cpu: CpuSnapshot?,
        mem: MemorySnapshot?,
        net: NetworkSnapshot?,
        bat: BatterySnapshot?,
    ) {
        val cpuText = cpu?.takeIf { it.source != CpuSource.UNAVAILABLE }?.let {
            val prefix = if (it.source == CpuSource.FREQ_ESTIMATE) "~" else ""
            prefix + Format.percent(it.utilization)
        }
        val memText = mem?.takeIf { it.totalBytes > 0 }?.let {
            Format.percent((it.usedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f))
        }
        // Zero totals = TrafficStats unsupported, same rule as the readout.
        val netText = net?.takeIf { it.totalRxBytes != 0L || it.totalTxBytes != 0L }?.let {
            "↓${compactBits(it.rxBytesPerSec)} ↑${compactBits(it.txBytesPerSec)}"
        }
        val batText = bat?.let { b ->
            buildString {
                append("${b.levelPercent}%")
                // Whole degrees: the card is too small for tenths.
                b.temperatureC?.let { append(String.format(Locale.US, " · %.0f°C", it)) }
            }
        }
        overlayView?.setData(
            cpuText, memText, netText, batText,
            settings.accent.controlLightArgb.toInt(),
        )
    }

    /** Main thread only. Adds/removes the overlay window to match settings. */
    private fun syncOverlay(settings: SysMonSettings) {
        val allowed = Settings.canDrawOverlays(this)
        if (settings.overlayEnabled && !allowed) {
            // Special access revoked behind our back: flip the toggle so it
            // tells the truth; the settings collector then re-reconciles.
            scope.launch { Graph.settings.setOverlayEnabled(false) }
        }
        val wantShown = settings.overlayEnabled && allowed
        if (wantShown && overlayView == null) {
            attachOverlay(settings)
        } else if (!wantShown) {
            detachOverlay()
        }
    }

    /** Main thread only. */
    private fun attachOverlay(settings: SysMonSettings) {
        val view = OverlayMonitorView(this)
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (settings.overlayX >= 0) settings.overlayX else (16 * density).toInt()
            y = if (settings.overlayY >= 0) settings.overlayY else (120 * density).toInt()
        }
        view.setOnTouchListener(OverlayDragListener(params, view))
        try {
            windowManager?.addView(view, params)
            overlayView = view
            overlayParams = params
        } catch (_: Exception) {
            // addView can refuse mid-revoke; the next reconcile retries.
        }
    }

    /** Main thread only. */
    private fun detachOverlay() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
                // Already detached by a teardown race.
            }
        }
        overlayView = null
        overlayParams = null
    }

    /**
     * Whole-card drag with the standard touch slop; the end position is
     * persisted so the card comes back where the user left it. Positions are
     * clamped to the display so a rotation can never strand it off-screen.
     */
    private inner class OverlayDragListener(
        private val params: WindowManager.LayoutParams,
        private val view: View,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@CpuNotificationService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val metrics = resources.displayMetrics
                        params.x = (startX + dx)
                            .coerceIn(0, (metrics.widthPixels - view.width).coerceAtLeast(0))
                        params.y = (startY + dy)
                            .coerceIn(0, (metrics.heightPixels - view.height).coerceAtLeast(0))
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (_: Exception) {
                            // View got detached mid-drag; nothing to move.
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        scope.launch { Graph.settings.setOverlayPosition(params.x, params.y) }
                    } else {
                        v.performClick()
                    }
                }
            }
            return true
        }
    }

    /** One log row; false ends the loop (file full or unwritable). */
    private suspend fun logOnce(): Boolean {
        val sample = LogSample(
            timeMillis = System.currentTimeMillis(),
            cpu = runCatching { logCpuMonitor.sample() }.getOrNull(),
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

        MEM_NOTIFICATION_ID -> buildReadoutNotification(
            MEM_CHANNEL_ID,
            R.drawable.ic_stat_memory,
            getString(R.string.mem_notif_title, DASH),
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

        // Also static: the overlay itself is the live surface — this exists
        // only because a foreground service must show SOME notification.
        OVERLAY_NOTIFICATION_ID -> buildReadoutNotification(
            OVERLAY_CHANNEL_ID,
            R.drawable.ic_stat_cpu,
            getString(R.string.overlay_notif_title),
            getString(R.string.overlay_notif_text),
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
        /**
         * Live status-bar icon for this tick; falls back to the static
         * [smallIcon] glyph when the value is unreadable (DASH states) — a
         * frozen number would be a lie, the glyph is not.
         */
        liveIcon: Bitmap? = null,
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
            .apply {
                if (liveIcon != null) {
                    setSmallIcon(IconCompat.createWithBitmap(liveIcon))
                } else {
                    setSmallIcon(smallIcon)
                }
            }
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .apply { if (largeIcon != null) setLargeIcon(largeIcon) }
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // One single-child group per readout (the channel id is a handy
            // stable key). Ungrouped, the ranker auto-groups 2+ readouts and
            // the status bar then shows only the summary's static launcher
            // glyph — hiding every live value icon. Single-child groups
            // render standalone, so nothing else changes.
            .setGroup(channelId)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // 31+ otherwise delays FGS notifications ~10s; a readout the
            // user just toggled on has to appear immediately.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * The "usage box" (large icon): an accent-colored rounded tile with the
     * readout's headline value(s) — the same strings as the title, tilde
     * included — over an optional utilization drawing: faint per-core [bars]
     * for the CPU, a bottom [fill] bar for memory/battery, neither for the
     * network (its two stacked rate lines fill the tile). Rendered, not a
     * layout, so it can be the large icon.
     */
    private fun renderTile(
        accentArgb: Long,
        lines: List<String>,
        bars: List<Float> = emptyList(),
        fill: Float? = null,
    ): Bitmap {
        val size = BOX_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = accentArgb.toInt()
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 40f, 40f, paint)
        if (bars.isNotEmpty()) {
            paint.color = 0x4DFFFFFF // 30% white
            val gap = 8f
            val barWidth = (size - gap * (bars.size + 1)) / bars.size
            bars.forEachIndexed { index, utilization ->
                val height = (size - 32f) * utilization.coerceIn(0f, 1f)
                val left = gap + index * (barWidth + gap)
                // Clip inside the rounded corners rather than clipping the
                // bars to a path: cheaper and invisible at icon size.
                canvas.drawRect(left, size - 8f - height, left + barWidth, size - 8f, paint)
            }
        }
        if (fill != null) {
            // Same idiom as the core bars: a fainter track under a 30%-white
            // fill, sitting where the bars would.
            val top = size - 48f
            val bottom = size - 20f
            paint.color = 0x26FFFFFF // 15% white
            canvas.drawRect(20f, top, size - 20f, bottom, paint)
            paint.color = 0x4DFFFFFF // 30% white
            canvas.drawRect(20f, top, 20f + (size - 40f) * fill.coerceIn(0f, 1f), bottom, paint)
        }
        paint.color = Color.WHITE
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        val maxWidth = size - 24f
        // One line sits dead center; two share the tile as stacked halves.
        // Both lines shrink together so mixed sizes never look ragged.
        val baseSize = if (lines.size == 1) 84f else 64f
        paint.textSize = baseSize
        val widest = lines.maxOf { paint.measureText(it) }
        if (widest > maxWidth) paint.textSize = baseSize * maxWidth / widest
        lines.forEachIndexed { index, line ->
            val centerY = size * (2 * index + 1) / (2f * lines.size)
            val baseline = centerY - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(line, size / 2f, baseline, paint)
        }
        return bitmap
    }

    /**
     * Live status-bar small icon: an opaque white rounded box with the value
     * punched out of it (so the digits are holes in the box). Status-bar
     * icons are pure alpha masks — the system discards color and keeps the
     * silhouette, which is exactly what a punch-out is. Repainting it every
     * tick is the "animated box on the top bar".
     */
    private fun renderStatusValue(text: String): Bitmap {
        val size = STATUS_ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        // A hair of inset so the box outline never touches the icon bounds
        // (some status bars shave edge pixels when scaling).
        canvas.drawRoundRect(4f, 4f, size - 4f, size - 4f, 16f, 16f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 68f
        val maxWidth = size - 22f
        val measured = paint.measureText(text)
        if (measured > maxWidth) paint.textSize = 68f * maxWidth / measured
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, size / 2f, baseline, paint)
        return bitmap
    }

    /**
     * Net status-bar icon: two stacked compact rates (down over up) as plain
     * text with no box — box padding would make two lines unreadable at
     * status-bar size. Both lines shrink together.
     */
    private fun renderStatusRates(down: String, up: String): Bitmap {
        val size = STATUS_ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 46f
        val widest = maxOf(paint.measureText(down), paint.measureText(up))
        if (widest > size) paint.textSize = 46f * size / widest
        listOf(down, up).forEachIndexed { index, line ->
            val centerY = size * (2 * index + 1) / 4f
            val baseline = centerY - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(line, size / 2f, baseline, paint)
        }
        return bitmap
    }

    /**
     * Rate for the tile and status icon: value plus one letter ("1.2M",
     * "340K", "0"), bits like [Format.bitsPerSec] but too terse for anything
     * except an icon — which is why it lives here and not in core.
     */
    private fun compactBits(bytesPerSec: Double): String {
        val bps = bytesPerSec * 8
        fun value(v: Double): String =
            String.format(Locale.US, if (v >= 10) "%.0f" else "%.1f", v)
        return when {
            bps >= 1_000_000_000 -> value(bps / 1_000_000_000) + "G"
            bps >= 1_000_000 -> value(bps / 1_000_000) + "M"
            bps >= 1_000 -> value(bps / 1_000) + "K"
            else -> String.format(Locale.US, "%.0f", bps)
        }
    }

    /**
     * "3.6 / 5.6 GB" — used and total sharing one unit so the title stays
     * short. Total RAM is ≥1 GB on anything that can install this app, but
     * degrade to two full [Format.bytes] values rather than ever printing a
     * wrong number.
     */
    private fun usedOfTotal(usedBytes: Long, totalBytes: Long): String {
        val gb = 1024.0 * 1024.0 * 1024.0
        return if (totalBytes >= gb) {
            String.format(Locale.US, "%.1f / %.1f GB", usedBytes / gb, totalBytes / gb)
        } else {
            "${Format.bytes(usedBytes)} / ${Format.bytes(totalBytes)}"
        }
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
                MEM_CHANNEL_ID,
                getString(R.string.mem_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.mem_notif_channel_desc)
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
        manager.createNotificationChannel(
            NotificationChannel(
                OVERLAY_CHANNEL_ID,
                getString(R.string.overlay_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.overlay_notif_channel_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CPU_CHANNEL_ID = "cpu_readout"
        private const val NET_CHANNEL_ID = "net_readout"
        private const val BAT_CHANNEL_ID = "battery_readout"
        private const val MEM_CHANNEL_ID = "memory_readout"
        private const val LOG_CHANNEL_ID = "logging_status"
        private const val OVERLAY_CHANNEL_ID = "overlay_status"
        // Anchor priority CPU > NET > BAT > MEM > OVERLAY > LOG; new ids slot
        // in above LOG (the anchor of last resort) so the earlier ids keep
        // their meaning.
        private const val CPU_NOTIFICATION_ID = 1
        private const val NET_NOTIFICATION_ID = 2
        private const val BAT_NOTIFICATION_ID = 3
        private const val LOG_NOTIFICATION_ID = 4
        private const val MEM_NOTIFICATION_ID = 5
        private const val OVERLAY_NOTIFICATION_ID = 6
        private const val UPDATE_INTERVAL_MS = 2_000L
        private const val BOX_SIZE_PX = 256

        /** ~24 dp at 4x — the status bar scales the alpha mask down from here. */
        private const val STATUS_ICON_SIZE_PX = 96

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
