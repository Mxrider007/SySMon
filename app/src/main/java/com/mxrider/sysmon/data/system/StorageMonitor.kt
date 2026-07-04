package com.mxrider.sysmon.data.system

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.mxrider.sysmon.core.StorageMonitor
import com.mxrider.sysmon.core.StorageSnapshot
import com.mxrider.sysmon.core.VolumeInfo
import java.io.File

/**
 * Storage sampler over [StorageManager.getStorageVolumes]. Volume directories
 * are only exposed on API 30+; below that only the primary volume (the data
 * partition) is measured. Always reports at least one "Internal storage"
 * primary volume, even when the volume list is unavailable.
 */
class DefaultStorageMonitor(context: Context) : StorageMonitor {

    private val appContext = context.applicationContext
    private val storageManager =
        appContext.getSystemService(Context.STORAGE_SERVICE) as? StorageManager

    override suspend fun sample(): StorageSnapshot {
        val volumes = ArrayList<VolumeInfo>()
        try {
            val storageVolumes = storageManager?.storageVolumes.orEmpty()
            for (volume in storageVolumes) {
                try {
                    // Read-only counts too: write-protected SD cards report
                    // MEDIA_MOUNTED_READ_ONLY and StatFs works fine on them.
                    if (volume.state != Environment.MEDIA_MOUNTED &&
                        volume.state != Environment.MEDIA_MOUNTED_READ_ONLY
                    ) {
                        continue
                    }
                    val directory: File? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        volume.directory
                    } else if (volume.isPrimary) {
                        Environment.getDataDirectory()
                    } else {
                        null // Path not exposed below API 30 for secondary volumes.
                    }
                    if (directory == null) continue
                    val info = statVolume(
                        path = directory.absolutePath,
                        label = volume.getDescription(appContext) ?: DEFAULT_LABEL,
                        isPrimary = volume.isPrimary,
                        isRemovable = volume.isRemovable,
                    ) ?: continue
                    volumes.add(info)
                } catch (_: Exception) {
                    // Skip this volume, keep the rest.
                }
            }
        } catch (_: Exception) {
            // Fall through to the data-partition fallback below.
        }

        if (volumes.none { it.isPrimary }) {
            val fallback = try {
                statVolume(
                    path = Environment.getDataDirectory().absolutePath,
                    label = DEFAULT_LABEL,
                    isPrimary = true,
                    isRemovable = false,
                )
            } catch (_: Exception) {
                null
            }
            if (fallback != null) volumes.add(0, fallback)
        }

        return StorageSnapshot(volumes)
    }

    private fun statVolume(
        path: String,
        label: String,
        isPrimary: Boolean,
        isRemovable: Boolean,
    ): VolumeInfo? {
        val stat = try {
            StatFs(path)
        } catch (_: Exception) {
            return null
        }
        val total = stat.blockCountLong * stat.blockSizeLong
        if (total <= 0L) return null
        val free = (stat.availableBlocksLong * stat.blockSizeLong).coerceIn(0L, total)
        return VolumeInfo(
            label = label,
            totalBytes = total,
            freeBytes = free,
            usedBytes = total - free,
            isPrimary = isPrimary,
            isRemovable = isRemovable,
        )
    }

    private companion object {
        const val DEFAULT_LABEL = "Internal storage"
    }
}
