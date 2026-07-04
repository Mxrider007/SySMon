package com.mxrider.sysmon.data.system

import android.app.ActivityManager
import android.content.Context
import com.mxrider.sysmon.core.MemoryMonitor
import com.mxrider.sysmon.core.MemorySnapshot
import java.io.File

/**
 * Memory sampler backed by [ActivityManager.getMemoryInfo], enriched with
 * cached/swap figures from `/proc/meminfo` (usually world-readable). Any
 * failure degrades to `-1` for cached and `0` for swap.
 */
class DefaultMemoryMonitor(context: Context) : MemoryMonitor {

    private val activityManager =
        context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    override suspend fun sample(): MemorySnapshot {
        var total = 0L
        var avail = 0L
        var threshold = 0L
        var low = false
        try {
            val am = activityManager
            if (am != null) {
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                total = info.totalMem
                avail = info.availMem
                threshold = info.threshold
                low = info.lowMemory
            }
        } catch (_: Exception) {
            // Keep zeroed defaults.
        }

        var cached = -1L
        var swapTotal = 0L
        var swapFree = 0L
        try {
            File(PROC_MEMINFO).bufferedReader().useLines { lines ->
                for (line in lines) {
                    when {
                        line.startsWith("Cached:") -> cached = parseKbToBytes(line)
                        line.startsWith("SwapTotal:") -> swapTotal = parseKbToBytes(line).coerceAtLeast(0L)
                        line.startsWith("SwapFree:") -> swapFree = parseKbToBytes(line).coerceAtLeast(0L)
                    }
                }
            }
        } catch (_: Exception) {
            cached = -1L
            swapTotal = 0L
            swapFree = 0L
        }

        return MemorySnapshot(
            totalBytes = total,
            availBytes = avail,
            usedBytes = (total - avail).coerceAtLeast(0L),
            lowMemory = low,
            thresholdBytes = threshold,
            cachedBytes = cached,
            swapTotalBytes = swapTotal,
            swapFreeBytes = swapFree,
        )
    }

    /** Parses a line like `Cached:  3847828 kB` into bytes; -1 on failure. */
    private fun parseKbToBytes(line: String): Long {
        val kb = line.substringAfter(':')
            .trim()
            .substringBefore(' ')
            .toLongOrNull() ?: return -1L
        return kb * 1024L
    }

    private companion object {
        const val PROC_MEMINFO = "/proc/meminfo"
    }
}
