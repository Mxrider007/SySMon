package com.mxrider.sysmon.data.logging

import com.mxrider.sysmon.core.PerfLogger

/** First/last recorded timestamps and surviving row count of the whole ring. */
data class LogBounds(
    val firstMs: Long,
    val lastMs: Long,
    val rows: Long,
)

/**
 * One window of the perf log, time-bucketed and ready to chart. All metric
 * arrays share [times]' length and indexing; only buckets that actually
 * contain rows are kept, so consecutive entries can be any distance apart —
 * that distance is the honest record of when logging ran.
 *
 * Bucket values are means of the samples that really carried the metric; a
 * metric blank for every sample in a bucket is NaN there ("unavailable"),
 * never 0. Power is stored as magnitude — the battery-level series carries
 * the charge/discharge direction, same division as the Battery panel.
 */
class TimelineSeries(
    /** Bucket-center epoch millis, ascending. */
    val times: LongArray,
    /** 0..100. */
    val cpuPct: FloatArray,
    val memUsedBytes: FloatArray,
    /** 0..100. */
    val batteryPct: FloatArray,
    /** Magnitude, watts. */
    val powerW: FloatArray,
    val batteryTempC: FloatArray,
    val netRxBytesPerSec: FloatArray,
    val netTxBytesPerSec: FloatArray,
    /** Fraction of the bucket's rows recorded with the screen on, 0..1. */
    val screenOnFraction: FloatArray,
    /** Largest total-RAM figure seen in the window; 0 = never reported. */
    val memTotalBytes: Long,
    val bucketMillis: Long,
    val windowStartMs: Long,
    val windowEndMs: Long,
    /**
     * A time step above this is a recording gap and breaks the chart line;
     * 3× the larger of the bucket size and the window's median sample
     * spacing, so a slow log cadence (5 min) zoomed into a short window is
     * not misread as hundreds of gaps.
     */
    val gapThresholdMs: Long,
    /** Rows aggregated inside the window. */
    val sampleCount: Int,
)

/**
 * Streaming parser/aggregator over [PerfLogger.readRows] for the Timeline
 * screen. Never loads the ring into memory: one pass for [bounds], one
 * window-filtered pass for [load], both O(buckets) in heap. Malformed rows
 * are skipped, I/O failures degrade to null — the timeline shows "nothing
 * recorded", it does not crash.
 */
class PerfLogReader(private val logger: PerfLogger) {

    /**
     * One cheap pass: min/max `time_unix_ms` plus the row count. Min AND max
     * (not first/last seen) because a wall-clock step while logging can leave
     * the append order locally out of time order. Null = empty log.
     */
    suspend fun bounds(): LogBounds? = try {
        logger.readRows { rows ->
            var first = Long.MAX_VALUE
            var last = Long.MIN_VALUE
            var n = 0L
            for (line in rows) {
                val t = line.split(',', limit = 3).getOrNull(1)?.toLongOrNull() ?: continue
                if (t < first) first = t
                if (t > last) last = t
                n++
            }
            if (n > 0) LogBounds(first, last, n) else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Aggregates every row with `startMs <= t <= endMs` into at most
     * [maxBuckets] buckets. The default targets ~2 buckets per rendered px
     * on a phone-width chart.
     */
    suspend fun load(startMs: Long, endMs: Long, maxBuckets: Int = 720): TimelineSeries? {
        val span = (endMs - startMs).coerceAtLeast(1L)
        val bucketMs = ((span + maxBuckets - 1) / maxBuckets).coerceAtLeast(1_000L)
        val bucketCount = ((span + bucketMs - 1) / bucketMs).toInt().coerceAtLeast(1)

        val rowsPerBucket = IntArray(bucketCount)
        val screenOnRows = IntArray(bucketCount)
        val sums = Array(METRIC_COUNT) { DoubleArray(bucketCount) }
        val counts = Array(METRIC_COUNT) { IntArray(bucketCount) }
        var memTotalMax = 0L
        var rowsInWindow = 0

        try {
            logger.readRows { rows ->
                for (line in rows) {
                    val f = line.split(',')
                    // Schema is 18 columns since it was introduced; anything
                    // shorter is a torn or foreign row.
                    if (f.size < 18) continue
                    val t = f[1].toLongOrNull() ?: continue
                    if (t < startMs || t > endMs) continue
                    var b = ((t - startMs) / bucketMs).toInt()
                    if (b >= bucketCount) b = bucketCount - 1
                    rowsInWindow++
                    rowsPerBucket[b]++
                    if (f[17] == "1") screenOnRows[b]++
                    // Empty cell = the metric was unavailable at that sample;
                    // it simply doesn't contribute (NaN if a whole bucket is
                    // blank) — averaging in a fabricated 0 is exactly what
                    // this app promises never to do.
                    accumulate(CPU, b, f[2].toDoubleOrNull(), sums, counts)
                    accumulate(MEM_USED, b, f[5].toDoubleOrNull(), sums, counts)
                    accumulate(BAT_PCT, b, f[8].toDoubleOrNull(), sums, counts)
                    accumulate(POWER_W, b, f[13].toDoubleOrNull()?.let { kotlin.math.abs(it) }, sums, counts)
                    accumulate(BAT_TEMP, b, f[14].toDoubleOrNull(), sums, counts)
                    accumulate(NET_RX, b, f[15].toDoubleOrNull(), sums, counts)
                    accumulate(NET_TX, b, f[16].toDoubleOrNull(), sums, counts)
                    f[7].toLongOrNull()?.let { if (it > memTotalMax) memTotalMax = it }
                }
            }
        } catch (_: Exception) {
            return null
        }

        var kept = 0
        for (b in 0 until bucketCount) if (rowsPerBucket[b] > 0) kept++
        val times = LongArray(kept)
        val out = Array(METRIC_COUNT) { FloatArray(kept) }
        val screenFrac = FloatArray(kept)
        var k = 0
        for (b in 0 until bucketCount) {
            if (rowsPerBucket[b] == 0) continue
            times[k] = startMs + b * bucketMs + bucketMs / 2
            for (m in 0 until METRIC_COUNT) {
                out[m][k] = if (counts[m][b] > 0) {
                    (sums[m][b] / counts[m][b]).toFloat()
                } else {
                    Float.NaN
                }
            }
            screenFrac[k] = screenOnRows[b].toFloat() / rowsPerBucket[b]
            k++
        }

        return TimelineSeries(
            times = times,
            cpuPct = out[CPU],
            memUsedBytes = out[MEM_USED],
            batteryPct = out[BAT_PCT],
            powerW = out[POWER_W],
            batteryTempC = out[BAT_TEMP],
            netRxBytesPerSec = out[NET_RX],
            netTxBytesPerSec = out[NET_TX],
            screenOnFraction = screenFrac,
            memTotalBytes = memTotalMax,
            bucketMillis = bucketMs,
            windowStartMs = startMs,
            windowEndMs = endMs,
            gapThresholdMs = 3 * maxOf(bucketMs, medianDelta(times)),
            sampleCount = rowsInWindow,
        )
    }

    private fun accumulate(
        metric: Int,
        bucket: Int,
        value: Double?,
        sums: Array<DoubleArray>,
        counts: Array<IntArray>,
    ) {
        if (value == null || value.isNaN()) return
        sums[metric][bucket] += value
        counts[metric][bucket]++
    }

    private fun medianDelta(times: LongArray): Long {
        if (times.size < 2) return 0L
        val deltas = LongArray(times.size - 1) { times[it + 1] - times[it] }
        deltas.sort()
        return deltas[deltas.size / 2]
    }

    private companion object {
        const val CPU = 0
        const val MEM_USED = 1
        const val BAT_PCT = 2
        const val POWER_W = 3
        const val BAT_TEMP = 4
        const val NET_RX = 5
        const val NET_TX = 6
        const val METRIC_COUNT = 7
    }
}
