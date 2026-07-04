package com.mxrider.sysmon.data.system

import android.os.Build
import android.os.SystemClock
import com.mxrider.sysmon.core.CoreState
import com.mxrider.sysmon.core.CpuMonitor
import com.mxrider.sysmon.core.CpuSnapshot
import com.mxrider.sysmon.core.CpuSource
import com.mxrider.sysmon.core.CpuStaticInfo
import java.io.File

/**
 * CPU sampler. Tries `/proc/stat` first each sample (unreadable for apps since
 * Android 8, but still works on some ROMs); falls back to estimating
 * utilization from per-core frequency scaling via sysfs. Never throws — every
 * file access degrades to `online = false` / `UNAVAILABLE`.
 */
class DefaultCpuMonitor : CpuMonitor {

    private val lock = Any()

    private val coreCount: Int = detectCoreCount()

    /** -1 = not cached yet; caches only successful reads (>0). */
    private val minFreqCache = LongArray(coreCount) { -1L }
    private val maxFreqCache = LongArray(coreCount) { -1L }

    /** Previous /proc/stat counters; total == -1 means "no previous sample". */
    private var prevAggTotal = -1L
    private var prevAggBusy = 0L
    private val prevCoreTotal = LongArray(coreCount) { -1L }
    private val prevCoreBusy = LongArray(coreCount)

    override val staticInfo: CpuStaticInfo by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        buildStaticInfo()
    }

    override suspend fun sample(): CpuSnapshot {
        val uptime = SystemClock.elapsedRealtime()
        return sampleProcStat(uptime) ?: sampleFreqEstimate(uptime)
    }

    /* ------------------------------ /proc/stat ------------------------------ */

    private class StatLine(val total: Long, val busy: Long)

    /** Returns null when /proc/stat is unreadable or unparsable. */
    private fun sampleProcStat(uptime: Long): CpuSnapshot? {
        var aggregate: StatLine? = null
        val perCore = arrayOfNulls<StatLine>(coreCount)
        try {
            File(PROC_STAT).bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (!line.startsWith("cpu")) break // cpu lines are contiguous at the top
                    val tokens = line.split(WHITESPACE)
                    val label = tokens[0]
                    if (label == "cpu") {
                        aggregate = parseStatLine(tokens)
                    } else {
                        val index = label.substring(3).toIntOrNull()
                        if (index != null && index in 0 until coreCount) {
                            perCore[index] = parseStatLine(tokens)
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) {
            return null
        }
        val agg = aggregate ?: return null

        var overall = 0f
        val coreUtil = FloatArray(coreCount)
        synchronized(lock) {
            val hadPrev = prevAggTotal >= 0
            if (hadPrev) {
                val dTotal = agg.total - prevAggTotal
                val dBusy = agg.busy - prevAggBusy
                overall = if (dTotal > 0) (dBusy.toFloat() / dTotal).coerceIn(0f, 1f) else 0f
                for (i in 0 until coreCount) {
                    val cur = perCore[i] ?: continue
                    val pt = prevCoreTotal[i]
                    if (pt < 0) continue
                    val dt = cur.total - pt
                    val db = cur.busy - prevCoreBusy[i]
                    coreUtil[i] = if (dt > 0) (db.toFloat() / dt).coerceIn(0f, 1f) else 0f
                }
            }
            prevAggTotal = agg.total
            prevAggBusy = agg.busy
            for (i in 0 until coreCount) {
                val cur = perCore[i]
                if (cur != null) {
                    prevCoreTotal[i] = cur.total
                    prevCoreBusy[i] = cur.busy
                } else {
                    prevCoreTotal[i] = -1L
                    prevCoreBusy[i] = 0L
                }
            }
        }

        val cores = ArrayList<CoreState>(coreCount)
        for (i in 0 until coreCount) {
            // Offline cores drop out of /proc/stat.
            val online = perCore[i] != null
            cores.add(
                CoreState(
                    index = i,
                    curFreqKhz = if (online) readCurFreqKhz(i) else 0L,
                    minFreqKhz = minFreq(i),
                    maxFreqKhz = maxFreq(i),
                    utilization = coreUtil[i],
                    online = online,
                )
            )
        }
        return CpuSnapshot(
            utilization = overall,
            perCore = cores,
            source = CpuSource.PROC_STAT,
            uptimeMillis = uptime,
        )
    }

    /**
     * Parses one "cpu ..." line. Fields: user nice system idle iowait irq
     * softirq steal (guest/guest_nice already counted in user/nice).
     */
    private fun parseStatLine(tokens: List<String>): StatLine? {
        if (tokens.size < 5) return null
        val v = LongArray(8)
        for (i in v.indices) {
            v[i] = if (i + 1 < tokens.size) tokens[i + 1].toLongOrNull() ?: 0L else 0L
        }
        val busy = v[0] + v[1] + v[2] + v[5] + v[6] + v[7]
        val idle = v[3] + v[4]
        return StatLine(total = busy + idle, busy = busy)
    }

    /* ---------------------------- Frequency fallback --------------------------- */

    private fun sampleFreqEstimate(uptime: Long): CpuSnapshot {
        val cores = ArrayList<CoreState>(coreCount)
        var onlineCount = 0
        var utilSum = 0f
        for (i in 0 until coreCount) {
            val min = minFreq(i)
            val max = maxFreq(i)
            val cur = readCurFreqKhz(i)
            if (cur <= 0L) {
                cores.add(CoreState(i, 0L, min, max, 0f, online = false))
                continue
            }
            val util = if (max > min) {
                ((cur - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            onlineCount++
            utilSum += util
            cores.add(CoreState(i, cur, min, max, util, online = true))
        }
        return if (onlineCount == 0) {
            CpuSnapshot(0f, cores, CpuSource.UNAVAILABLE, uptime)
        } else {
            CpuSnapshot(utilSum / onlineCount, cores, CpuSource.FREQ_ESTIMATE, uptime)
        }
    }

    private fun readCurFreqKhz(core: Int): Long {
        val v = readLongFile("$CPU_SYS_DIR/cpu$core/cpufreq/scaling_cur_freq")
        return if (v > 0) v else 0L
    }

    private fun minFreq(core: Int): Long = cachedFreq(minFreqCache, core, "cpuinfo_min_freq")

    private fun maxFreq(core: Int): Long = cachedFreq(maxFreqCache, core, "cpuinfo_max_freq")

    private fun cachedFreq(cache: LongArray, core: Int, fileName: String): Long {
        if (core !in 0 until coreCount) return 0L
        synchronized(lock) {
            val cached = cache[core]
            if (cached >= 0) return cached
        }
        val v = readLongFile("$CPU_SYS_DIR/cpu$core/cpufreq/$fileName")
        if (v > 0) {
            synchronized(lock) { cache[core] = v }
            return v
        }
        return 0L
    }

    private fun readLongFile(path: String): Long = try {
        File(path).readText().trim().toLongOrNull() ?: -1L
    } catch (_: Exception) {
        -1L
    }

    /* -------------------------------- Static info ------------------------------ */

    private fun buildStaticInfo(): CpuStaticInfo {
        var min = Long.MAX_VALUE
        var max = 0L
        for (i in 0 until coreCount) {
            val lo = minFreq(i)
            val hi = maxFreq(i)
            if (lo in 1 until min) min = lo
            if (hi > max) max = hi
        }
        if (min == Long.MAX_VALUE) min = 0L
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.HARDWARE
        }
        return CpuStaticInfo(
            socModel = soc,
            hardware = Build.HARDWARE,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            coreCount = coreCount,
            is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),
            minFreqKhz = min,
            maxFreqKhz = max,
        )
    }

    private companion object {
        const val PROC_STAT = "/proc/stat"
        const val CPU_SYS_DIR = "/sys/devices/system/cpu"
        val WHITESPACE = Regex("\\s+")
        val CORE_DIR = Regex("cpu[0-9]+")

        fun detectCoreCount(): Int {
            val fromSys = try {
                File(CPU_SYS_DIR).listFiles { f: File -> f.isDirectory && CORE_DIR.matches(f.name) }
                    ?.size ?: 0
            } catch (_: Exception) {
                0
            }
            return if (fromSys > 0) {
                fromSys
            } else {
                Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            }
        }
    }
}
