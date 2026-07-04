package com.mxrider.sysmon.data.apps

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.UserHandle
import android.os.storage.StorageManager
import com.mxrider.sysmon.core.AppEntry
import com.mxrider.sysmon.core.AppRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Installed-app inventory via [PackageManager] plus per-app storage stats via
 * [StorageStatsManager] (the latter silently degrades to `null` without usage
 * access). Results are cached: the full list for 30 s, per-package storage for
 * 60 s. All loading happens on the caller's dispatcher (callers use IO).
 */
class DefaultAppRepository(context: Context) : AppRepository {

    private val appContext = context.applicationContext
    private val pm: PackageManager get() = appContext.packageManager

    @Volatile
    private var cachedApps: Pair<Long, List<AppEntry>>? = null

    /** packageName -> (elapsedRealtime timestamp, bytes or null when unavailable). */
    private val storageCache = ConcurrentHashMap<String, Pair<Long, Long?>>()

    override suspend fun installedApps(): List<AppEntry> {
        val now = SystemClock.elapsedRealtime()
        cachedApps?.let { (timestamp, list) ->
            if (now - timestamp < APP_CACHE_MS) return list
        }
        val list = loadApps()
        cachedApps = now to list
        return list
    }

    override suspend fun appStorageBytes(packageName: String, uid: Int): Long? {
        val now = SystemClock.elapsedRealtime()
        storageCache[packageName]?.let { (timestamp, bytes) ->
            if (now - timestamp < STORAGE_CACHE_MS) return bytes
        }
        val bytes: Long? = try {
            val ssm = appContext.getSystemService(StorageStatsManager::class.java)
            if (ssm == null) {
                null
            } else {
                val stats = ssm.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT,
                    packageName,
                    UserHandle.getUserHandleForUid(uid),
                )
                stats.appBytes + stats.dataBytes + stats.cacheBytes
            }
        } catch (e: SecurityException) {
            null // No usage access.
        } catch (e: Exception) {
            null // NameNotFound / IO / OEM quirks.
        }
        storageCache[packageName] = now to bytes
        return bytes
    }

    private fun loadApps(): List<AppEntry> {
        val infos: List<ApplicationInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (e: Exception) {
            emptyList()
        }
        return infos.mapNotNull { toEntry(it) }
    }

    private fun toEntry(ai: ApplicationInfo): AppEntry? {
        val pkg = ai.packageName ?: return null

        val pkgInfo: PackageInfo? = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
        } catch (e: Exception) {
            null
        }

        val versionCode: Long = when {
            pkgInfo == null -> 0L
            Build.VERSION.SDK_INT >= 28 -> pkgInfo.longVersionCode
            else -> @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
        }

        val label = try {
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            pkg
        }

        val apkSizeBytes: Long = try {
            var size = ai.sourceDir?.let { File(it).length() } ?: 0L
            ai.splitSourceDirs?.forEach { split -> size += File(split).length() }
            size
        } catch (e: Exception) {
            0L
        }

        val installer: String? = try {
            if (Build.VERSION.SDK_INT >= 30) {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkg)
            }
        } catch (e: Exception) {
            null
        }

        return AppEntry(
            packageName = pkg,
            label = label,
            isSystem = (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
            versionName = pkgInfo?.versionName,
            versionCode = versionCode,
            targetSdk = ai.targetSdkVersion,
            minSdk = ai.minSdkVersion,
            installTime = pkgInfo?.firstInstallTime ?: 0L,
            updateTime = pkgInfo?.lastUpdateTime ?: 0L,
            apkSizeBytes = apkSizeBytes,
            installerPackage = installer,
            enabled = ai.enabled,
            uid = ai.uid,
        )
    }

    private companion object {
        const val APP_CACHE_MS = 30_000L
        const val STORAGE_CACHE_MS = 60_000L
    }
}
