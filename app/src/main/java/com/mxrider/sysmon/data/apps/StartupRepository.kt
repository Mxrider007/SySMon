package com.mxrider.sysmon.data.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.mxrider.sysmon.core.StartupImpact
import com.mxrider.sysmon.core.StartupRepository
import com.mxrider.sysmon.core.StartupRow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * "Startup" rows: apps declaring BOOT_COMPLETED receivers, grouped per
 * package. Enabled = app enabled AND at least one receiver component enabled.
 * Impact is a simple receiver-count heuristic.
 */
class DefaultStartupRepository(context: Context) : StartupRepository {

    private val appContext = context.applicationContext
    private val pm: PackageManager get() = appContext.packageManager

    override suspend fun startupRows(): List<StartupRow> {
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_ALL
        val resolved: List<ResolveInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryBroadcastReceivers(intent, flags)
            }
        } catch (e: Exception) {
            emptyList()
        }

        val byPackage = HashMap<String, MutableList<ResolveInfo>>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            byPackage.getOrPut(pkg) { ArrayList() }.add(ri)
        }

        val rows = ArrayList<StartupRow>(byPackage.size)
        for ((pkg, infos) in byPackage) {
            // One binder call per package: let a superseded reload stop here.
            currentCoroutineContext().ensureActive()
            val ai = applicationInfoOrNull(pkg) ?: continue // Skip unresolvable rows.
            val receiverNames = infos.mapNotNull { it.activityInfo?.name?.substringAfterLast('.') }
            if (receiverNames.isEmpty()) continue
            val label = try {
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg
            }
            rows += StartupRow(
                packageName = pkg,
                label = label,
                isSystem = (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                enabled = ai.enabled && infos.any { receiverEnabled(it) },
                receiverNames = receiverNames,
                impact = when {
                    receiverNames.size >= 3 -> StartupImpact.HIGH
                    receiverNames.size == 2 -> StartupImpact.MEDIUM
                    else -> StartupImpact.LOW
                },
            )
        }
        return rows
    }

    private fun receiverEnabled(ri: ResolveInfo): Boolean {
        val info = ri.activityInfo ?: return false
        val name = info.name ?: return false
        return try {
            when (pm.getComponentEnabledSetting(ComponentName(info.packageName, name))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> info.enabled
                else -> false
            }
        } catch (e: Exception) {
            info.enabled
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
}
