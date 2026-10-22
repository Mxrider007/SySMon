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
 * access). Results are cached: the full list until a package changes (and at
 * most 5 min), per-package storage for 60 s. Both caches are keyed on the
 * [PackageEvents] generation, so an install, update or uninstall is visible to
 * the very next load instead of being masked by a still-fresh cache. All
 * loading happens on the caller's dispatcher (callers use IO).
 */
class DefaultAppRepository(context: Context) : AppRepository {

    private val appContext = context.applicationContext
    private val pm: PackageManager get() = appContext.packageManager

    /** (elapsedRealtime timestamp, [PackageEvents] generation, list). */
    @Volatile
    private var cachedApps: Triple<Long, Long, List<AppEntry>>? = null

    /** packageName -> (elapsedRealtime timestamp, bytes or null when unavailable). */
    private val storageCache = ConcurrentHashMap<String, Pair<Long, Long?>>()

    /** The [PackageEvents] generation [storageCache] was filled under. */
    @Volatile
    private var storageGeneration = PackageEvents.generation.value

    override suspend fun installedApps(): List<AppEntry> {
        val now = SystemClock.elapsedRealtime()
        // Read the generation BEFORE loading: a package change that lands
        // mid-load leaves this result stamped with the old generation, so the
        // event-driven reload that follows misses the cache and sees it.
        val generation = PackageEvents.generation.value
        cachedApps?.let { (timestamp, cachedGeneration, list) ->
            if (cachedGeneration == generation && now - timestamp < APP_CACHE_MS) return list
        }
        val list = loadApps()
        cachedApps = Triple(now, generation, list)
        return list
    }

    override suspend fun appStorageBytes(packageName: String, uid: Int): Long? {
        val generation = PackageEvents.generation.value
        if (generation != storageGeneration) {
            // Installs, updates and uninstalls change sizes; start over.
            storageCache.clear()
            storageGeneration = generation
        }
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
                // dataBytes already includes the cache directories; adding
                // cacheBytes again would count every app's cache twice.
                stats.appBytes + stats.dataBytes
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
        // distinctBy is a guard, not a nicety: package name is the row key for
        // every table built from this list, and Compose's LazyColumn throws a
        // hard IllegalArgumentException on a duplicate key rather than
        // degrading. Stock Android does not return duplicates for one user,
        // but an OEM package manager doing so would crash the default tab, and
        // one line at the source removes the whole class of failure.
        return infos.mapNotNull { toEntry(it) }.distinctBy { it.packageName }
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
        const val APP_CACHE_MS = 5 * 60_000L
        const val STORAGE_CACHE_MS = 60_000L
    }
}
