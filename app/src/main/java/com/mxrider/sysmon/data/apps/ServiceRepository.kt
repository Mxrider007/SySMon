package com.mxrider.sysmon.data.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import com.mxrider.sysmon.core.ServiceRepository
import com.mxrider.sysmon.core.ServiceRow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Declared services of installed apps. Android exposes no live service state
 * to third-party apps, so this is a static manifest inventory. Services are
 * fetched per package (one binder call each) to avoid
 * TransactionTooLargeException on app-heavy devices.
 */
class DefaultServiceRepository(context: Context) : ServiceRepository {

    private val appContext = context.applicationContext
    private val pm: PackageManager get() = appContext.packageManager

    override suspend fun serviceRows(): List<ServiceRow> {
        val apps: List<ApplicationInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (e: Exception) {
            emptyList()
        }

        val rows = ArrayList<ServiceRow>(256)
        for (ai in apps) {
            val pkg = ai.packageName ?: continue
            // One binder call per package: let a superseded reload stop here.
            currentCoroutineContext().ensureActive()
            val pkgInfo: PackageInfo = try {
                val flags = PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, flags)
                }
            } catch (e: Exception) {
                continue // NameNotFound, TransactionTooLarge, OEM quirks.
            }

            val services = pkgInfo.services ?: continue
            if (services.isEmpty()) continue

            val label = try {
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg
            }
            val isSystem =
                (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

            for (si in services) {
                val fqcn = si.name ?: continue
                rows += ServiceRow(
                    packageName = pkg,
                    appLabel = label,
                    serviceName = fqcn.substringAfterLast('.'),
                    fqcn = fqcn,
                    exported = si.exported,
                    enabled = si.enabled,
                    permission = si.permission,
                    foregroundServiceTypes = foregroundServiceTypeNames(si),
                    isSystemApp = isSystem,
                )
            }
        }
        // Same guard as AppRepository: package+class is the table's row key,
        // and a duplicate key is an unrecoverable Compose exception.
        return rows.distinctBy { "${it.packageName}/${it.fqcn}" }
    }

    private fun foregroundServiceTypeNames(si: ServiceInfo): String {
        if (Build.VERSION.SDK_INT < 29) return ""
        val mask = try {
            si.foregroundServiceType
        } catch (e: Throwable) {
            0
        }
        if (mask <= 0) return ""
        val names = ArrayList<String>(4)
        for ((bit, name) in FGS_TYPE_NAMES) {
            if (mask and bit != 0) names += name
        }
        return names.joinToString(", ")
    }

    private companion object {
        /**
         * ServiceInfo.FOREGROUND_SERVICE_TYPE_* bit values (stable across
         * releases); literals keep this compiling and safe on every API level,
         * including types whose constants only exist on newer SDKs.
         */
        val FGS_TYPE_NAMES: List<Pair<Int, String>> = listOf(
            0x0001 to "dataSync",
            0x0002 to "mediaPlayback",
            0x0004 to "phoneCall",
            0x0008 to "location",
            0x0010 to "connectedDevice",
            0x0020 to "mediaProjection",
            0x0040 to "camera",
            0x0080 to "microphone",
            0x0100 to "health",
            0x0200 to "remoteMessaging",
            0x0400 to "systemExempted",
            0x0800 to "shortService",
            0x1000 to "fileManagement",
            0x2000 to "mediaProcessing",
            0x40000000 to "specialUse",
        )
    }
}
