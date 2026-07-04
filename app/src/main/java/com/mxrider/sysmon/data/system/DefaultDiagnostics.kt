package com.mxrider.sysmon.data.system

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
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
        "${info.versionName} ($code)"
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
