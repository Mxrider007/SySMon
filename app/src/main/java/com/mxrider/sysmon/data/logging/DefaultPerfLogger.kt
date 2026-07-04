package com.mxrider.sysmon.data.logging

import android.content.Context
import android.net.Uri
import com.mxrider.sysmon.core.CpuSource
import com.mxrider.sysmon.core.LogSample
import com.mxrider.sysmon.core.LogStatus
import com.mxrider.sysmon.core.PerfLogger
import com.mxrider.sysmon.core.averageCurFreqKhz
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * CSV performance log at `files/perf_log.csv` — deliberately OUTSIDE the
 * backed-up `datastore` path (backup_rules.xml is include-only): a file
 * allowed to grow to 50 MB would blow the ~25 MB auto-backup quota, and a
 * performance history of one device is meaningless restored onto another.
 *
 * All file access is serialized by a mutex and runs on Dispatchers.IO. The
 * first status collection (and the first append after process death) re-reads
 * the file, so counters survive restarts without a second bookkeeping file.
 */
class DefaultPerfLogger(context: Context) : PerfLogger {

    private val appContext = context.applicationContext
    private val file: File get() = File(appContext.filesDir, LOG_FILE)
    private val mutex = Mutex()
    private var initialized = false
    private val state = MutableStateFlow(LogStatus(0, 0, null))

    override val status: Flow<LogStatus> = state.onStart {
        mutex.withLock { ensureInitLocked() }
    }

    override suspend fun append(sample: LogSample): Boolean = mutex.withLock {
        ensureInitLocked()
        val current = state.value
        if (current.isFull) return@withLock false
        val newSize = withContext(Dispatchers.IO) {
            try {
                val f = file
                f.appendText(
                    buildString {
                        if (!f.exists() || f.length() == 0L) appendLine(HEADER)
                        appendLine(renderRow(sample))
                    },
                )
                f.length()
            } catch (_: Exception) {
                -1L
            }
        }
        if (newSize < 0) return@withLock false
        state.value = LogStatus(
            sizeBytes = newSize,
            rowCount = current.rowCount + 1,
            startedAt = current.startedAt ?: sample.timeMillis,
        )
        true
    }

    override suspend fun clear() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    file.delete()
                } catch (_: Exception) {
                    // Re-read below reports whatever is really on disk.
                }
            }
            state.value = withContext(Dispatchers.IO) { readStatus() }
            initialized = true
        }
    }

    override suspend fun exportTo(uri: Uri): Boolean = mutex.withLock {
        ensureInitLocked()
        withContext(Dispatchers.IO) {
            try {
                val f = file
                if (!f.exists()) return@withContext false
                // "wt" truncates: the picker may hand back an existing document.
                val out = appContext.contentResolver.openOutputStream(uri, "wt")
                    ?: return@withContext false
                out.use { stream -> f.inputStream().use { it.copyTo(stream) } }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun ensureInitLocked() {
        if (initialized) return
        state.value = withContext(Dispatchers.IO) { readStatus() }
        initialized = true
    }

    /** Streams the file once: size, data-row count, first row's timestamp. */
    private fun readStatus(): LogStatus = try {
        val f = file
        if (!f.exists() || f.length() == 0L) {
            LogStatus(0, 0, null)
        } else {
            var rows = 0L
            var startedAt: Long? = null
            f.bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    if (line.isBlank()) return@forEach
                    rows++
                    if (startedAt == null) {
                        startedAt = line.split(',', limit = 3).getOrNull(1)?.toLongOrNull()
                    }
                }
            }
            LogStatus(sizeBytes = f.length(), rowCount = rows, startedAt = startedAt)
        }
    } catch (_: Exception) {
        LogStatus(0, 0, null)
    }

    private fun renderRow(sample: LogSample): String {
        val cpu = sample.cpu
        val cpuPct = if (cpu != null && cpu.source != CpuSource.UNAVAILABLE) {
            String.format(Locale.US, "%.1f", cpu.utilization * 100)
        } else {
            ""
        }
        val net = sample.network
        // Zero totals after a sample = TrafficStats unsupported (the service
        // applies the same rule to the readout): rates would be fabricated.
        val netOk = net != null && (net.totalRxBytes != 0L || net.totalTxBytes != 0L)
        val bat = sample.battery
        return listOf(
            ISO_STAMP.format(Instant.ofEpochMilli(sample.timeMillis)),
            sample.timeMillis.toString(),
            cpuPct,
            cpu?.source?.name?.lowercase(Locale.US) ?: "",
            cpu?.averageCurFreqKhz()?.takeIf { it > 0 }?.toString() ?: "",
            sample.memory?.usedBytes?.toString() ?: "",
            sample.memory?.availBytes?.toString() ?: "",
            sample.memory?.totalBytes?.toString() ?: "",
            bat?.levelPercent?.toString() ?: "",
            bat?.status ?: "",
            bat?.plugType ?: "",
            bat?.currentMicroA?.let { (it / 1000).toString() } ?: "",
            bat?.voltageMilliV?.toString() ?: "",
            bat?.powerWatts?.let { String.format(Locale.US, "%.3f", it) } ?: "",
            bat?.temperatureC?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            if (netOk) String.format(Locale.US, "%.0f", net.rxBytesPerSec) else "",
            if (netOk) String.format(Locale.US, "%.0f", net.txBytesPerSec) else "",
            if (sample.screenOn) "1" else "0",
        ).joinToString(",")
    }

    private companion object {
        const val LOG_FILE = "perf_log.csv"

        /** Column names use unambiguous units; empty cell = unavailable. */
        const val HEADER = "time_iso,time_unix_ms,cpu_pct,cpu_source,cpu_avg_freq_khz," +
            "mem_used_bytes,mem_avail_bytes,mem_total_bytes," +
            "bat_pct,bat_status,bat_plug,bat_current_ma,bat_voltage_mv,bat_power_w,bat_temp_c," +
            "net_rx_bytes_per_s,net_tx_bytes_per_s,screen_on"

        /** Local time with offset: sortable and still human-readable in a spreadsheet. */
        val ISO_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                .withZone(ZoneId.systemDefault())
    }
}
