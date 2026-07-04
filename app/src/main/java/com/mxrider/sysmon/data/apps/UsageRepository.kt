package com.mxrider.sysmon.data.apps

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import com.mxrider.sysmon.core.UsageRange
import com.mxrider.sysmon.core.UsageRepository
import com.mxrider.sysmon.core.UsageRow
import java.util.Calendar

/**
 * Screen time for TODAY by streaming [UsageStatsManager.queryEvents] (precise,
 * and within the system's ~10-day event retention); DAYS_7/DAYS_30 from
 * [UsageStatsManager.queryAndAggregateUsageStats], because raw events are
 * pruned after ~10 days and a 30-day event query silently returns only the
 * retained tail. Launch counts are event-derived, so they are null for the
 * multi-day ranges. Optional per-uid network totals from [NetworkStatsManager].
 * Everything degrades to empty lists / `null` byte counts when usage access is
 * missing or an OEM quirk throws. Runs on the caller's dispatcher (callers use IO).
 */
class DefaultUsageRepository(context: Context) : UsageRepository {

    private val appContext = context.applicationContext
    private val pm: PackageManager get() = appContext.packageManager

    override suspend fun usageRows(range: UsageRange, includeNetwork: Boolean): List<UsageRow> {
        val now = System.currentTimeMillis()
        val start = rangeStart(range, now)

        val eventBased = range == UsageRange.TODAY
        val accs = if (eventBased) collectEvents(start, now) else collectAggregates(start, now)
        if (accs.isEmpty()) return emptyList()

        val wifiByUid: Map<Int, Long>? =
            if (includeNetwork) queryNetworkBytes(wifi = true, start = start, end = now) else null
        val mobileByUid: Map<Int, Long>? =
            if (includeNetwork) queryNetworkBytes(wifi = false, start = start, end = now) else null

        val rows = ArrayList<UsageRow>(accs.size)
        for ((pkg, acc) in accs) {
            if (acc.screenTimeMs <= 0L && acc.launches <= 0) continue
            val ai = applicationInfoOrNull(pkg) ?: continue // Uninstalled since -> skip.
            val label = try {
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg
            }
            rows += UsageRow(
                packageName = pkg,
                label = label,
                isSystem = (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                screenTimeMs = acc.screenTimeMs,
                lastUsed = maxOf(acc.lastEnter, acc.lastExit),
                launches = if (eventBased) acc.launches else null,
                wifiBytes = wifiByUid?.let { it[ai.uid] ?: 0L },
                mobileBytes = mobileByUid?.let { it[ai.uid] ?: 0L },
            )
        }
        return rows
    }

    /** Per-package foreground accumulator. */
    private class UsageAcc {
        var screenTimeMs = 0L
        var launches = 0
        var lastEnter = 0L
        var lastExit = 0L
        var openSince = -1L
    }

    private fun collectEvents(start: Long, now: Long): Map<String, UsageAcc> {
        val result = HashMap<String, UsageAcc>()
        val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return result
        val events = try {
            usm.queryEvents(start, now)
        } catch (e: Exception) {
            null // SecurityException without usage access, OEM quirks.
        } ?: return result

        try {
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                when (event.eventType) {
                    // ACTIVITY_RESUMED == MOVE_TO_FOREGROUND (1): compile-time
                    // constant, so this matches on every API level.
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        val acc = result.getOrPut(pkg) { UsageAcc() }
                        if (acc.openSince < 0L) {
                            acc.openSince = event.timeStamp
                            // A fresh enter >= 1s after the last exit counts as a launch.
                            if (acc.lastExit == 0L || event.timeStamp - acc.lastExit >= 1_000L) {
                                acc.launches++
                            }
                        }
                        acc.lastEnter = event.timeStamp
                    }
                    // ACTIVITY_PAUSED == MOVE_TO_BACKGROUND (2).
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        val acc = result.getOrPut(pkg) { UsageAcc() }
                        if (acc.openSince >= 0L) {
                            acc.screenTimeMs += (event.timeStamp - acc.openSince).coerceAtLeast(0L)
                            acc.openSince = -1L
                        } else if (acc.lastEnter == 0L && acc.lastExit == 0L) {
                            // First event in the window is an exit: the session
                            // was already open at `start` - credit that segment.
                            acc.screenTimeMs += (event.timeStamp - start).coerceAtLeast(0L)
                            acc.lastEnter = start
                        }
                        acc.lastExit = event.timeStamp
                    }
                }
            }
        } catch (e: Exception) {
            // Keep whatever was accumulated before the quirk.
        }

        // Cap still-open foreground intervals at 'now'; a currently-foreground
        // app counts as used right now.
        for (acc in result.values) {
            if (acc.openSince >= 0L) {
                acc.screenTimeMs += (now - acc.openSince).coerceAtLeast(0L)
                acc.openSince = -1L
                acc.lastExit = now
            }
        }
        return result
    }

    /**
     * Aggregated per-package stats for multi-day ranges. The system keeps
     * daily/weekly/monthly buckets long after raw events are pruned, so this
     * is the only truthful source for 7/30-day screen time. Launch counts are
     * not available here.
     */
    private fun collectAggregates(start: Long, now: Long): Map<String, UsageAcc> {
        val result = HashMap<String, UsageAcc>()
        val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return result
        val stats = try {
            usm.queryAndAggregateUsageStats(start, now)
        } catch (e: Exception) {
            null // SecurityException without usage access, OEM quirks.
        } ?: return result

        for ((pkg, us) in stats) {
            val screenTime = us.totalTimeInForeground
            if (screenTime <= 0L) continue
            val acc = UsageAcc()
            acc.screenTimeMs = screenTime
            acc.lastEnter = us.lastTimeUsed.coerceAtLeast(0L)
            acc.lastExit = acc.lastEnter
            result[pkg] = acc
        }
        return result
    }

    /**
     * Sums rx+tx bytes per uid for the given transport, or `null` when the
     * query fails (no usage access, missing subscriber info, OEM quirks).
     */
    @Suppress("DEPRECATION")
    private fun queryNetworkBytes(wifi: Boolean, start: Long, end: Long): Map<Int, Long>? {
        // Pre-Q, a null subscriberId template never matches a real mobile
        // network: the query "succeeds" with an empty result and every app
        // would show a confident 0 B. Report unknown instead (renders as "-").
        if (!wifi && Build.VERSION.SDK_INT < 29) return null
        val nsm = appContext.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return null
        val networkType = if (wifi) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE
        return try {
            val stats = nsm.querySummary(networkType, null, start, end)
            val byUid = HashMap<Int, Long>()
            try {
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val total = bucket.rxBytes + bucket.txBytes
                    if (total > 0L) byUid[bucket.uid] = (byUid[bucket.uid] ?: 0L) + total
                }
            } finally {
                stats.close()
            }
            byUid
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun applicationInfoOrNull(packageName: String): ApplicationInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
    } catch (e: Exception) {
        null
    }

    private fun rangeStart(range: UsageRange, now: Long): Long = when (range) {
        UsageRange.TODAY -> {
            val cal = Calendar.getInstance()
            cal.timeInMillis = now
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
        UsageRange.DAYS_7 -> now - 7L * DAY_MS
        UsageRange.DAYS_30 -> now - 30L * DAY_MS
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
