package com.mxrider.sysmon.data.system

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.Build
import android.provider.Settings
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.core.CpuSource
import com.mxrider.sysmon.core.DiagnosticsReporter
import com.mxrider.sysmon.core.UsageAccess
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Support-path diagnostics. SysMon has no INTERNET permission, so there is no
 * in-app crash reporting; instead the last uncaught exception is persisted to
 * a private file and included in the Settings "Copy diagnostics" report that
 * users paste into a support email. The report is English-only by design —
 * its audience is the developer, not the user.
 */
class DefaultDiagnostics(
    context: Context,
    private val usageAccess: UsageAccess,
) : DiagnosticsReporter {

    private val appContext = context.applicationContext

    /**
     * Private sampler instances, for the same reason the monitoring service
     * keeps its own (see CpuNotificationService): CPU and GPU load are both
     * DELTA measurements over the window since that instance's last read.
     * Sampling the shared [Graph] monitors here would consume the Performance
     * screen's window, so pressing "Copy diagnostics" would silently distort
     * the very readings the report exists to describe.
     */
    private val cpuProbe by lazy { DefaultCpuMonitor() }
    private val gpuLoadProbe by lazy { DefaultGpuLoadMonitor() }

    /**
     * Persist uncaught exceptions, then delegate to the previous handler so
     * the system crash dialog and Play-vitals reporting still happen.
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(thread, throwable)
            } catch (_: Throwable) {
                // Never mask the original crash with a logging failure.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    override suspend fun buildReport(): String = withContext(Dispatchers.IO) {
        val samplers = samplerMatrix()
        buildString {
            appendLine("SysMon diagnostics")
            appendLine("App: ${appVersion()}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            totalRam()?.let { appendLine("RAM: $it") }
            val config = appContext.resources.configuration
            appendLine("Locale: ${config.locales.toLanguageTags()}, font scale ${config.fontScale}")
            appendLine("Usage access: ${if (usageAccess.hasPermission()) "granted" else "not granted"}")
            append(samplers)
            val crash = readLastCrash()
            if (crash == null) {
                appendLine("Last crash: none recorded")
            } else {
                appendLine()
                appendLine("---- Last recorded crash ----")
                append(crash)
            }
        }.trimEnd()
    }

    /**
     * What each sampler can actually see on THIS device — the answer to
     * every "why does CPU show ~ / why is X a dash" support mail in one
     * block, without asking the user to describe screens.
     */
    private suspend fun samplerMatrix(): String = buildString {
        appendLine("Samplers:")
        val cpu = runCatching { cpuProbe.sample() }.getOrNull()
        val online = cpu?.perCore?.count { it.online } ?: 0
        appendLine(
            "  CPU: ${cpu?.source?.name?.lowercase(Locale.US) ?: "unavailable"}" +
                " (${online}/${cpu?.perCore?.size ?: 0} cores online)",
        )
        val mem = runCatching { Graph.memory.sample() }.getOrNull()
        appendLine(
            "  Memory: ${if (mem != null && mem.totalBytes > 0) "ok" else "unavailable"}," +
                " meminfo cached ${if ((mem?.cachedBytes ?: -1L) >= 0L) "readable" else "blocked"}",
        )
        val rx = try {
            TrafficStats.getTotalRxBytes()
        } catch (_: Exception) {
            TrafficStats.UNSUPPORTED.toLong()
        }
        appendLine(
            "  TrafficStats: " +
                if (rx > 0) "supported" else "unsupported",
        )
        val bat = runCatching { Graph.battery.sample() }.getOrNull()
        appendLine(
            "  Battery: current=${present(bat?.currentMicroA)}," +
                " voltage=${present(bat?.voltageMilliV)}, temp=${present(bat?.temperatureC)}," +
                " charge counter=${present(bat?.chargeCounterMicroAh)}," +
                " design capacity=${present(bat?.designCapacityMAh)}," +
                " cycles=${present(bat?.cycleCount)}",
        )
        val storage = runCatching { Graph.storage.sample() }.getOrNull()
        appendLine("  Storage volumes: ${storage?.volumes?.size ?: 0}")
        val thermal = runCatching { Graph.thermal.sample() }.getOrNull()
        appendLine(
            "  Thermal: status=${thermal?.status?.name?.lowercase(Locale.US) ?: "unavailable"}," +
                " headroom=${present(thermal?.headroom)}," +
                " zones=${thermal?.zones?.size ?: 0}" +
                " (${thermal?.zoneAccess?.name?.lowercase(Locale.US) ?: "unknown"}," +
                " ${thermal?.hiddenZoneCount ?: 0} implausible)",
        )
        val gpuLoad = runCatching { gpuLoadProbe.sample() }.getOrNull()
        appendLine(
            "  GPU load node: " + if (gpuLoad != null) "readable" else "not readable",
        )
        appendLine(
            "  Overlay permission: " +
                if (Settings.canDrawOverlays(appContext)) "granted" else "not granted",
        )
    }

    private fun present(value: Any?): String = if (value != null) "yes" else "no"

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        val trace = StringWriter().also { sw ->
            PrintWriter(sw).use { throwable.printStackTrace(it) }
        }.toString()
        val text = buildString {
            appendLine("${UTC_STAMP.format(Instant.now())} on thread \"${thread.name}\"")
            appendLine("App: ${appVersion()}")
            append(trace.take(MAX_TRACE_CHARS))
            if (trace.length > MAX_TRACE_CHARS) appendLine("… [truncated]")
        }
        File(appContext.filesDir, CRASH_FILE).writeText(text)
    }

    private fun readLastCrash(): String? = try {
        val file = File(appContext.filesDir, CRASH_FILE)
        if (file.exists()) {
            file.readText().take(MAX_TRACE_CHARS + CRASH_HEADER_CHARS).takeIf { it.isNotBlank() }
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun appVersion(): String = try {
        val info: PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        // Package name identifies the edition (free vs .supporter) in
        // support emails; the version alone is identical across both.
        "${info.versionName} ($code) · ${appContext.packageName}"
    } catch (e: Exception) {
        "unknown"
    }

    private fun totalRam(): String? = try {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.let {
            val mi = ActivityManager.MemoryInfo()
            it.getMemoryInfo(mi)
            String.format(Locale.US, "%.1f GB", mi.totalMem / (1024.0 * 1024.0 * 1024.0))
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val CRASH_FILE = "last_crash.txt"

        /** Keeps the clipboard payload far below binder/clipboard limits. */
        const val MAX_TRACE_CHARS = 12_000
        const val CRASH_HEADER_CHARS = 400

        val UTC_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
                .withZone(ZoneOffset.UTC)
    }
}
