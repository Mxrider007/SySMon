package com.mxrider.sysmon.data.apps

import android.content.Context
import com.mxrider.sysmon.core.AppRepository
import com.mxrider.sysmon.core.ProcStatus
import com.mxrider.sysmon.core.ProcessRepository
import com.mxrider.sysmon.core.ProcessRow
import com.mxrider.sysmon.core.UsageAccess
import com.mxrider.sysmon.core.UsageRange
import com.mxrider.sysmon.core.UsageRepository
import java.util.Calendar
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * "Processes" rows built from today's usage data (Android exposes no real
 * per-process list to third-party apps). Status is a recency heuristic over
 * the last foreground time. Launchable non-system apps without any usage today
 * are appended so the table is useful even without usage access — INACTIVE
 * when the heuristic had data to say so, UNKNOWN when usage access is missing
 * and no status claim would be honest.
 */
class DefaultProcessRepository(
    context: Context,
    private val apps: AppRepository,
    private val usage: UsageRepository,
    private val usageAccess: UsageAccess,
) : ProcessRepository {

    private val appContext = context.applicationContext

    override suspend fun processRows(): List<ProcessRow> {
        val hasAccess = usageAccess.hasPermission()
        val now = System.currentTimeMillis()
        val todayStart = startOfToday(now)

        val usageRows = try {
            usage.usageRows(UsageRange.TODAY, includeNetwork = hasAccess)
        } catch (e: Exception) {
            emptyList()
        }
        val entries = try {
            apps.installedApps()
        } catch (e: Exception) {
            emptyList()
        }
        val entryByPkg = entries.associateBy { it.packageName }

        // One StorageStatsManager binder call per app; some OEMs answer each
        // in tens of ms, so a serial loop over ~100 rows takes seconds on
        // first load. A small concurrency cap hides the latency without
        // hammering the binder.
        val storageByPkg: Map<String, Long?> = if (hasAccess) {
            coroutineScope {
                val semaphore = Semaphore(STORAGE_LOOKUP_CONCURRENCY)
                usageRows.mapNotNull { row ->
                    val entry = entryByPkg[row.packageName] ?: return@mapNotNull null
                    async {
                        semaphore.withPermit {
                            row.packageName to apps.appStorageBytes(row.packageName, entry.uid)
                        }
                    }
                }.awaitAll().toMap()
            }
        } else {
            emptyMap()
        }

        val rows = ArrayList<ProcessRow>(usageRows.size + 32)
        val seen = HashSet<String>(usageRows.size * 2)

        for (row in usageRows) {
            seen += row.packageName
            val entry = entryByPkg[row.packageName]
            val networkTodayBytes: Long? =
                if (row.wifiBytes == null && row.mobileBytes == null) null
                else (row.wifiBytes ?: 0L) + (row.mobileBytes ?: 0L)
            val storageBytes: Long? = storageByPkg[row.packageName]
            rows += ProcessRow(
                packageName = row.packageName,
                label = row.label,
                isSystem = entry?.isSystem ?: row.isSystem,
                status = statusFor(row.lastUsed, now, todayStart),
                foregroundTimeTodayMs = row.screenTimeMs,
                lastUsedTime = row.lastUsed,
                storageBytes = storageBytes,
                networkTodayBytes = networkTodayBytes,
                uid = entry?.uid ?: -1,
            )
        }

        // Installed, launchable, non-system apps with no usage today.
        val pm = appContext.packageManager
        for (entry in entries) {
            if (entry.isSystem || entry.packageName in seen) continue
            val launchable = try {
                pm.getLaunchIntentForPackage(entry.packageName) != null
            } catch (e: Exception) {
                false
            }
            if (!launchable) continue
            rows += ProcessRow(
                packageName = entry.packageName,
                label = entry.label,
                isSystem = false,
                status = if (hasAccess) ProcStatus.INACTIVE else ProcStatus.UNKNOWN,
                foregroundTimeTodayMs = 0L,
                lastUsedTime = 0L,
                storageBytes = null,
                networkTodayBytes = null,
                uid = entry.uid,
            )
        }
        return rows
    }

    private fun statusFor(lastUsed: Long, now: Long, todayStart: Long): ProcStatus {
        if (lastUsed <= 0L) return ProcStatus.INACTIVE
        val age = now - lastUsed
        return when {
            age < 15L * 60_000L -> ProcStatus.ACTIVE
            age < 2L * 3_600_000L -> ProcStatus.RECENT
            lastUsed >= todayStart -> ProcStatus.CACHED
            else -> ProcStatus.INACTIVE
        }
    }

    private fun startOfToday(now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private companion object {
        const val STORAGE_LOOKUP_CONCURRENCY = 4
    }
}
