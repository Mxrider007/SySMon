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

/**
 * "Processes" rows built from today's usage data (Android exposes no real
 * per-process list to third-party apps). Status is a recency heuristic over
 * the last foreground time. Launchable non-system apps without any usage today
 * are appended as INACTIVE so the table is useful even without usage access.
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

        val rows = ArrayList<ProcessRow>(usageRows.size + 32)
        val seen = HashSet<String>(usageRows.size * 2)

        for (row in usageRows) {
            seen += row.packageName
            val entry = entryByPkg[row.packageName]
            val networkTodayBytes: Long? =
                if (row.wifiBytes == null && row.mobileBytes == null) null
                else (row.wifiBytes ?: 0L) + (row.mobileBytes ?: 0L)
            val storageBytes: Long? = if (hasAccess && entry != null) {
                apps.appStorageBytes(row.packageName, entry.uid)
            } else {
                null
            }
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
                status = ProcStatus.INACTIVE,
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
}
