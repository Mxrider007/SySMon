package com.mxrider.sysmon.data.logging

import android.content.Context
import android.net.Uri
import com.mxrider.sysmon.core.CpuSource
import com.mxrider.sysmon.core.LogSample
import com.mxrider.sysmon.core.LogStatus
import com.mxrider.sysmon.core.PerfLogger
import com.mxrider.sysmon.core.averageCurFreqKhz
import java.io.File
import java.io.RandomAccessFile
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
 * CSV performance log as a TWO-FILE RING at `files/perf_log.csv` (active) +
 * `files/perf_log.1.csv` (sealed previous) — deliberately OUTSIDE the
 * backed-up `datastore` path (backup_rules.xml is include-only): 50 MB would
 * blow the ~25 MB auto-backup quota, and a performance history of one device
 * is meaningless restored onto another.
 *
 * When the active file reaches half of [PerfLogger.MAX_LOG_BYTES] it becomes
 * the sealed previous file (replacing the older one) and a fresh active file
 * starts, so logging RUNS FOREVER inside the cap and only the oldest half
 * ever drops — a hard stop used to kill multi-day captures silently, which
 * is exactly what the feature is for. Status, export and clear all treat the
 * pair as one log: combined sizes and row counts, one header on export,
 * oldest surviving row as the start time.
 *
 * All file access is serialized by a mutex and runs on Dispatchers.IO. Init
 * (and rotation, which is rare) re-reads the files, so counters survive
 * restarts without a bookkeeping file and self-heal if a rename ever fails.
 */
class DefaultPerfLogger(context: Context) : PerfLogger {

    private val appContext = context.applicationContext
    private val activeFile: File get() = File(appContext.filesDir, LOG_FILE)
    private val previousFile: File get() = File(appContext.filesDir, PREVIOUS_FILE)
    private val mutex = Mutex()
    private var initialized = false
    private val state = MutableStateFlow(LogStatus(0, 0, null))

    // Split bookkeeping so a normal append is O(1); the exposed LogStatus is
    // always previous + active.
    private var activeSize = 0L
    private var activeRows = 0L
    private var activeStartedAt: Long? = null
    private var prevSize = 0L
    private var prevRows = 0L
    private var prevStartedAt: Long? = null

    override val status: Flow<LogStatus> = state.onStart {
        mutex.withLock { ensureInitLocked() }
    }

    override suspend fun append(sample: LogSample): Boolean = mutex.withLock {
        ensureInitLocked()
        val newSize = withContext(Dispatchers.IO) {
            try {
                val f = activeFile
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
        activeSize = newSize
        activeRows++
        if (activeStartedAt == null) activeStartedAt = sample.timeMillis
        if (activeSize >= ROTATE_BYTES) rotateLocked()
        publishLocked()
        true
    }

    override suspend fun clear() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    activeFile.delete()
                    previousFile.delete()
                } catch (_: Exception) {
                    // refresh below reports whatever is really on disk.
                }
                refreshFromDiskLocked()
            }
            initialized = true
            publishLocked()
        }
    }

    override suspend fun exportTo(uri: Uri): Boolean = mutex.withLock {
        ensureInitLocked()
        withContext(Dispatchers.IO) {
            try {
                if (!previousFile.exists() && !activeFile.exists()) return@withContext false
                // "wt" truncates: the picker may hand back an existing document.
                val out = appContext.contentResolver.openOutputStream(uri, "wt")
                    ?: return@withContext false
                out.bufferedWriter().use { writer ->
                    // One header for the whole ring; each file then contributes
                    // its data rows only (both carry their own header line).
                    writer.write(HEADER)
                    writer.newLine()
                    for (f in listOf(previousFile, activeFile)) {
                        if (!f.exists()) continue
                        f.bufferedReader().useLines { lines ->
                            lines.drop(1).forEach { line ->
                                if (line.isNotBlank()) {
                                    writer.write(line)
                                    writer.newLine()
                                }
                            }
                        }
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    override suspend fun <T> readRows(block: (Sequence<String>) -> T): T = mutex.withLock {
        ensureInitLocked()
        withContext(Dispatchers.IO) {
            val prev = previousFile.takeIf { it.exists() }
            val active = activeFile.takeIf { it.exists() }
            // Readers are opened via nested useLines (never a sequence builder:
            // an abandoned builder suspends inside useLines and leaks the file
            // handle until GC), so both close no matter how much of the
            // sequence the block consumes.
            when {
                prev == null && active == null -> block(emptySequence())
                prev == null -> active!!.bufferedReader().useLines { a -> block(dataRows(a)) }
                active == null -> prev.bufferedReader().useLines { p -> block(dataRows(p)) }
                else -> prev.bufferedReader().useLines { p ->
                    active.bufferedReader().useLines { a ->
                        block(dataRows(p) + dataRows(a))
                    }
                }
            }
        }
    }

    /** Strips the per-file header line and any blank lines, like [exportTo]. */
    private fun dataRows(lines: Sequence<String>): Sequence<String> =
        lines.drop(1).filter { it.isNotBlank() }

    /**
     * Seals the active file as the previous one (dropping the older half) and
     * re-reads disk truth — rotation is rare enough that a full rescan is
     * cheap, and it self-heals the counters if the rename failed.
     */
    private suspend fun rotateLocked() {
        withContext(Dispatchers.IO) {
            try {
                previousFile.delete()
                activeFile.renameTo(previousFile)
            } catch (_: Exception) {
                // refresh below reports whatever really happened.
            }
            refreshFromDiskLocked()
        }
    }

    private suspend fun ensureInitLocked() {
        if (initialized) return
        withContext(Dispatchers.IO) {
            repairTailNewline()
            refreshFromDiskLocked()
        }
        initialized = true
        publishLocked()
    }

    private fun publishLocked() {
        state.value = LogStatus(
            sizeBytes = prevSize + activeSize,
            rowCount = prevRows + activeRows,
            startedAt = prevStartedAt ?: activeStartedAt,
        )
    }

    /**
     * A process death mid-append can leave a partial last line with no
     * terminator; the next append would then concatenate onto it and corrupt
     * two rows into one. Only the active file can be torn — the previous file
     * is always a cleanly-appended file that got renamed.
     */
    private fun repairTailNewline() {
        try {
            val f = activeFile
            if (!f.exists() || f.length() == 0L) return
            RandomAccessFile(f, "rw").use { raf ->
                raf.seek(raf.length() - 1)
                if (raf.read() != '\n'.code) raf.write('\n'.code)
            }
        } catch (_: Exception) {
            // refreshFromDiskLocked() reports whatever is really on disk.
        }
    }

    /** Streams both files once: sizes, data-row counts, first timestamps. */
    private fun refreshFromDiskLocked() {
        val prev = scanFile(previousFile)
        prevSize = prev.sizeBytes
        prevRows = prev.rowCount
        prevStartedAt = prev.startedAt
        val active = scanFile(activeFile)
        activeSize = active.sizeBytes
        activeRows = active.rowCount
        activeStartedAt = active.startedAt
    }

    private fun scanFile(f: File): LogStatus = try {
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
        const val PREVIOUS_FILE = "perf_log.1.csv"

        /** Rotate at half the total cap: two files ≤ MAX_LOG_BYTES together. */
        const val ROTATE_BYTES = PerfLogger.MAX_LOG_BYTES / 2

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
