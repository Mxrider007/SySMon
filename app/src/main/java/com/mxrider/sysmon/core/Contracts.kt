package com.mxrider.sysmon.core

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.Flow

/**
 * Interfaces between the data layer and the UI layer. Implementations live in
 * `data.system`, `data.apps` and `settings`; they are wired in [com.mxrider.sysmon.Graph].
 *
 * All `sample()`/loader functions are expected to be called from a background
 * dispatcher and must never throw for permission or OEM quirks — degrade to
 * empty/`null`/`UNAVAILABLE` values instead.
 */

interface Sampler<T> {
    suspend fun sample(): T
}

interface CpuMonitor : Sampler<CpuSnapshot> {
    val staticInfo: CpuStaticInfo
}

interface MemoryMonitor : Sampler<MemorySnapshot>

/** Stateful: keeps previous counters so [sample] can return rates. */
interface NetworkMonitor : Sampler<NetworkSnapshot>

interface BatteryMonitor : Sampler<BatterySnapshot>

interface StorageMonitor : Sampler<StorageSnapshot>

interface GpuInfoProvider {
    /** Cached after the first successful query. */
    suspend fun get(): GpuInfo
}

/* ---------------------------- Apps & processes ---------------------------- */

interface AppRepository {
    suspend fun installedApps(): List<AppEntry>
    /** Total app+data+cache bytes; null without usage access. */
    suspend fun appStorageBytes(packageName: String, uid: Int): Long?
}

/** Gate for the special "Usage access" permission (PACKAGE_USAGE_STATS). */
interface UsageAccess {
    fun hasPermission(): Boolean
    fun settingsIntent(): Intent
}

/**
 * Support-path diagnostics. With no INTERNET permission there is no crash
 * reporter; the support channel is this clipboard report pasted into an email.
 */
interface DiagnosticsReporter {
    /**
     * Device/app/usage-access summary plus the last recorded crash, if any.
     * English-only by design — its audience is the developer.
     */
    suspend fun buildReport(): String
}

/** Status of the on-device performance log (CSV in app-private storage). */
data class LogStatus(
    val sizeBytes: Long,
    val rowCount: Long,
    /** Epoch millis of the oldest logged row; null while the log is empty. */
    val startedAt: Long?,
) {
    /** At the cap the logger refuses appends and the service flips logging off. */
    val isFull: Boolean get() = sizeBytes >= PerfLogger.MAX_LOG_BYTES
}

/**
 * Append-only CSV performance log in app-private storage. Nothing leaves the
 * device unless the user exports it themselves through the system document
 * picker — SysMon still has no INTERNET permission.
 */
interface PerfLogger {
    val status: Flow<LogStatus>

    /** Appends one row; false (nothing written) once the log file is full. */
    suspend fun append(sample: LogSample): Boolean

    suspend fun clear()

    /** Copies the CSV to [uri]; false on any I/O failure. */
    suspend fun exportTo(uri: Uri): Boolean

    companion object {
        /** Hard cap: bounded private storage, and a visible number the Settings caption can promise. */
        const val MAX_LOG_BYTES = 50L * 1024 * 1024
    }
}

interface UsageRepository {
    suspend fun usageRows(range: UsageRange, includeNetwork: Boolean): List<UsageRow>
}

interface ProcessRepository {
    suspend fun processRows(): List<ProcessRow>
}

interface TaskKiller {
    /** True below API 34, where killBackgroundProcesses still affects other apps. */
    fun canKill(): Boolean
    /** Best-effort background kill; returns false when unsupported on this API level. */
    fun kill(packageName: String): Boolean
    fun appInfoIntent(packageName: String): Intent
    fun launchIntent(packageName: String): Intent?
}

interface ServiceRepository {
    suspend fun serviceRows(): List<ServiceRow>
}

interface StartupRepository {
    suspend fun startupRows(): List<StartupRow>
}

interface UserRepository {
    suspend fun users(): List<UserRow>
}

/* -------------------------------- Settings -------------------------------- */

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class UpdateSpeed(val intervalMs: Long) {
    HIGH(500), NORMAL(1000), LOW(4000), PAUSED(0)
}

/** Sampling cadence of the on-device performance log. */
enum class LogInterval(val intervalMs: Long) {
    SEC_1(1_000), SEC_5(5_000), SEC_30(30_000), MIN_5(300_000)
}

enum class TabDestination { PROCESSES, PERFORMANCE, APP_HISTORY, STARTUP, USERS, DETAILS, SERVICES, SETTINGS }

/**
 * Accent palette. `light`/`dark` are the chart/series colors validated for
 * contrast and chroma on their mode's surfaces; `controlLight`/`controlDark`
 * are the Windows 11 control-fill accents (white text on light variant,
 * black text on dark variant).
 */
enum class AccentOption(
    val lightArgb: Long,
    val darkArgb: Long,
    val controlLightArgb: Long,
    val controlDarkArgb: Long,
) {
    BLUE(0xFF106CBC, 0xFF28A0DC, 0xFF0F6CBD, 0xFF4CC2FF),
    PURPLE(0xFF8864B8, 0xFF9884E0, 0xFF8764B8, 0xFFB4A0FF),
    TEAL(0xFF009094, 0xFF30A8AC, 0xFF038387, 0xFF4CDDE0),
    GREEN(0xFF107C10, 0xFF44B034, 0xFF107C10, 0xFF6CCB5F),
    ORANGE(0xFFCC500C, 0xFFE47004, 0xFFCA5010, 0xFFFF9349),
    RED(0xFFC42C1C, 0xFFF85450, 0xFFC42B1C, 0xFFFF6B61),
}

data class SysMonSettings(
    val themeMode: ThemeMode,
    val accent: AccentOption,
    val updateSpeed: UpdateSpeed,
    val startPage: TabDestination,
    val keepScreenOn: Boolean,
    val showSystemApps: Boolean,
    /**
     * Ongoing CPU-readout notification (foreground service). Default OFF —
     * a monitoring app that self-starts a persistent service would betray
     * the "does nothing you didn't ask for" brand.
     */
    val cpuNotification: Boolean,
    /**
     * Live usage tiles (large icons) on every readout notification —
     * per-core bars for CPU, fill bars for memory and battery, stacked
     * rates for network. Sub-option of the readouts; defaults ON because
     * tiles only show once the user has already opted into a readout.
     */
    val notificationBoxes: Boolean,
    /**
     * Ongoing network-speed notification (same foreground service). Rates
     * come from on-device TrafficStats counters — no INTERNET permission.
     */
    val netNotification: Boolean,
    /**
     * Ongoing battery readout notification (same foreground service): level,
     * power draw and temperature straight from BatteryManager.
     */
    val batteryNotification: Boolean,
    /**
     * Ongoing memory readout notification (same foreground service): used,
     * total and available RAM from ActivityManager.
     */
    val memoryNotification: Boolean,
    /**
     * On-device performance logging (CSV in private storage, same foreground
     * service). Unlike the readouts it keeps sampling while the screen is
     * off — capturing what happens when nobody is looking is the point.
     */
    val logging: Boolean,
    val logInterval: LogInterval,
    /**
     * Always-on-top floating mini-monitor (same foreground service): live
     * CPU, memory, network and battery in a small draggable card. Default
     * OFF, needs the "Display over other apps" special access, and sampling
     * pauses while the screen is off like the readouts.
     */
    val overlayEnabled: Boolean,
    /** Last dragged overlay position in screen px; -1 = never moved (defaults apply). */
    val overlayX: Int,
    val overlayY: Int,
    val onboardingDone: Boolean,
) {
    companion object {
        val DEFAULT = SysMonSettings(
            themeMode = ThemeMode.SYSTEM,
            accent = AccentOption.BLUE,
            updateSpeed = UpdateSpeed.NORMAL,
            startPage = TabDestination.PROCESSES,
            keepScreenOn = false,
            showSystemApps = false,
            cpuNotification = false,
            notificationBoxes = true,
            netNotification = false,
            batteryNotification = false,
            memoryNotification = false,
            logging = false,
            logInterval = LogInterval.SEC_5,
            overlayEnabled = false,
            overlayX = -1,
            overlayY = -1,
            onboardingDone = false,
        )
    }
}

interface SettingsStore {
    val settings: Flow<SysMonSettings>
    suspend fun setThemeMode(value: ThemeMode)
    suspend fun setAccent(value: AccentOption)
    suspend fun setUpdateSpeed(value: UpdateSpeed)
    suspend fun setStartPage(value: TabDestination)
    suspend fun setKeepScreenOn(value: Boolean)
    suspend fun setShowSystemApps(value: Boolean)
    suspend fun setCpuNotification(value: Boolean)
    suspend fun setNotificationBoxes(value: Boolean)
    suspend fun setNetNotification(value: Boolean)
    suspend fun setBatteryNotification(value: Boolean)
    suspend fun setMemoryNotification(value: Boolean)
    suspend fun setLogging(value: Boolean)
    suspend fun setLogInterval(value: LogInterval)
    suspend fun setOverlayEnabled(value: Boolean)
    suspend fun setOverlayPosition(x: Int, y: Int)
    suspend fun setOnboardingDone(value: Boolean)
}
