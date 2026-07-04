package com.mxrider.sysmon.core

/**
 * Immutable data snapshots produced by the data layer, consumed by ViewModels.
 * All sizes are bytes, all frequencies kHz, all times epoch millis unless noted.
 */

enum class CpuSource { PROC_STAT, FREQ_ESTIMATE, UNAVAILABLE }

data class CoreState(
    val index: Int,
    val curFreqKhz: Long,
    val minFreqKhz: Long,
    val maxFreqKhz: Long,
    /** 0f..1f, estimated from frequency scaling (or /proc/stat when readable). */
    val utilization: Float,
    val online: Boolean,
)

data class CpuSnapshot(
    /** 0f..1f overall utilization. */
    val utilization: Float,
    val perCore: List<CoreState>,
    val source: CpuSource,
    val uptimeMillis: Long,
)

data class CpuStaticInfo(
    val socModel: String,
    val hardware: String,
    val abi: String,
    val coreCount: Int,
    val is64Bit: Boolean,
    val minFreqKhz: Long,
    val maxFreqKhz: Long,
)

data class MemorySnapshot(
    val totalBytes: Long,
    val availBytes: Long,
    val usedBytes: Long,
    val lowMemory: Boolean,
    /** Threshold at which the system considers itself low on memory. */
    val thresholdBytes: Long,
    /** From /proc/meminfo when readable, else -1. */
    val cachedBytes: Long,
    val swapTotalBytes: Long,
    val swapFreeBytes: Long,
)

enum class NetTransport { WIFI, CELLULAR, ETHERNET, VPN, NONE, OTHER }

data class NetworkSnapshot(
    val rxBytesPerSec: Double,
    val txBytesPerSec: Double,
    /** Counters since boot, all interfaces. */
    val totalRxBytes: Long,
    val totalTxBytes: Long,
    val mobileRxBytesPerSec: Double,
    val mobileTxBytesPerSec: Double,
    val transport: NetTransport,
    /** e.g. SSID (if permitted), carrier name, or interface name. */
    val networkName: String?,
    val ipv4: String?,
    val ipv6: String?,
)

data class BatterySnapshot(
    val levelPercent: Int,
    val isCharging: Boolean,
    /** "AC", "USB", "Wireless", "Dock" or null when on battery. */
    val plugType: String?,
    /** Instantaneous current in microamperes; negative = discharging. Null if unsupported. */
    val currentMicroA: Long?,
    val voltageMilliV: Int?,
    /** Signed power in watts derived from current * voltage; null if unsupported. */
    val powerWatts: Double?,
    val temperatureC: Double?,
    val health: String,
    val technology: String?,
    /** Charge cycles, API 34+; null when unavailable. */
    val cycleCount: Int?,
    val status: String,
)

data class VolumeInfo(
    val label: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val usedBytes: Long,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
)

data class StorageSnapshot(val volumes: List<VolumeInfo>)

data class GpuInfo(
    val renderer: String?,
    val vendor: String?,
    val glVersion: String?,
)

/* ---------------------------- Apps & processes ---------------------------- */

data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val versionName: String?,
    val versionCode: Long,
    val targetSdk: Int,
    val minSdk: Int,
    val installTime: Long,
    val updateTime: Long,
    val apkSizeBytes: Long,
    val installerPackage: String?,
    val enabled: Boolean,
    val uid: Int,
)

enum class ProcStatus { ACTIVE, RECENT, CACHED, INACTIVE }

data class ProcessRow(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val status: ProcStatus,
    val foregroundTimeTodayMs: Long,
    val lastUsedTime: Long,
    /** App + data + cache via StorageStatsManager; null without usage access. */
    val storageBytes: Long?,
    /** Wi-Fi + mobile bytes today via NetworkStatsManager; null without usage access. */
    val networkTodayBytes: Long?,
    val uid: Int,
)

enum class UsageRange { TODAY, DAYS_7, DAYS_30 }

data class UsageRow(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val screenTimeMs: Long,
    val lastUsed: Long,
    /** Event-derived; null for multi-day ranges where events are pruned. */
    val launches: Int?,
    val wifiBytes: Long?,
    val mobileBytes: Long?,
)

data class ServiceRow(
    val packageName: String,
    val appLabel: String,
    /** Short class name, e.g. "SyncService". */
    val serviceName: String,
    val fqcn: String,
    val exported: Boolean,
    val enabled: Boolean,
    val permission: String?,
    /** Human-readable foreground service types, empty when none declared. */
    val foregroundServiceTypes: String,
    val isSystemApp: Boolean,
)

enum class StartupImpact { LOW, MEDIUM, HIGH }

data class StartupRow(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val enabled: Boolean,
    val receiverNames: List<String>,
    /** Heuristic based on receiver count and app size. */
    val impact: StartupImpact,
)

data class UserRow(
    val handleId: Int,
    val name: String?,
    val isCurrent: Boolean,
    val isSystemUser: Boolean,
    val isManagedProfile: Boolean,
)
