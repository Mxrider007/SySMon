package com.mxrider.sysmon.core

import java.util.Locale
import kotlin.math.abs

/**
 * Formatting helpers shared by every screen. Windows convention: binary
 * magnitudes (1024) labeled KB/MB/GB, matching what Task Manager displays.
 */
object Format {

    fun bytes(bytes: Long): String {
        if (bytes < 0) return "—"
        val kb = 1024.0
        return when {
            bytes >= kb * kb * kb * kb -> String.format(Locale.US, "%.1f TB", bytes / (kb * kb * kb * kb))
            bytes >= kb * kb * kb -> String.format(Locale.US, "%.1f GB", bytes / (kb * kb * kb))
            bytes >= kb * kb -> String.format(Locale.US, "%.1f MB", bytes / (kb * kb))
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    /** Network throughput in bits per second, like Task Manager's graphs. */
    fun bitsPerSec(bytesPerSec: Double): String {
        val bps = bytesPerSec * 8
        return when {
            bps >= 1_000_000_000 -> String.format(Locale.US, "%.1f Gbps", bps / 1_000_000_000)
            bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000)
            bps >= 1_000 -> String.format(Locale.US, "%.0f Kbps", bps / 1_000)
            else -> String.format(Locale.US, "%.0f bps", bps)
        }
    }

    /** 0f..1f → "47%". */
    fun percent(fraction: Float): String =
        "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"

    fun freqGhz(khz: Long): String =
        if (khz <= 0) "—" else String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0)

    /** "3:42:05" (h:mm:ss) or "42:05" when under an hour. */
    fun duration(ms: Long): String {
        if (ms < 0) return "—"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Uptime style: "5 days 3:42:05". */
    fun uptime(ms: Long): String {
        val days = ms / 86_400_000
        val rest = duration(ms % 86_400_000).let { if (it.count { c -> c == ':' } == 1) "0:$it" else it }
        return if (days > 0) "$days d $rest" else rest
    }

    /** Coarse relative time: "Just now", "5 min ago", "2 h ago", "3 days ago". */
    fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMillis <= 0) return "Never"
        val d = abs(now - epochMillis)
        return when {
            d < 60_000 -> "Just now"
            d < 3_600_000 -> "${d / 60_000} min ago"
            d < 86_400_000 -> "${d / 3_600_000} h ago"
            else -> "${d / 86_400_000} days ago"
        }
    }

    fun watts(w: Double): String = String.format(Locale.US, "%.1f W", w)

    fun celsius(c: Double): String = String.format(Locale.US, "%.1f °C", c)
}
