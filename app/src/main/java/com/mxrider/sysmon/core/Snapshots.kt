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

/** Average current frequency of the online cores, 0 when unknown. */
fun CpuSnapshot.averageCurFreqKhz(): Long {
    val online = perCore.filter { it.online && it.curFreqKhz > 0 }
    if (online.isEmpty()) return 0L
    return online.sumOf { it.curFreqKhz } / online.size
}

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
    /** Fuel-gauge charge counter in microampere-hours; null if unsupported. */
    val chargeCounterMicroAh: Long?,
    /** ROM PowerProfile design capacity in mAh; null where blocked/absent. */
    val designCapacityMAh: Double?,
) {
    /**
     * Estimated FULL capacity in mAh, extrapolated from the fuel gauge:
     * charge counter scaled by the level. Levels under 20 % amplify gauge
     * error too much to be honest, so they return null.
     */
    fun estimatedFullCapacityMAh(): Double? {
        val counter = chargeCounterMicroAh ?: return null
        if (levelPercent < 20) return null
        return counter / 1000.0 * 100.0 / levelPercent
    }
}

/**
 * The OS's own throttling verdict (PowerManager, API 29+). This is the one
 * thermal signal Android guarantees to every app, so it anchors the panel on
 * devices whose sensor files are unreadable.
 */
enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNKNOWN }

/** Why the per-sensor list is empty — "blocked" and "none exist" are different truths. */
enum class ZoneAccess {
    /** At least one zone produced a plausible reading. */
    OK,

    /** `/sys/class/thermal` exists but SELinux/DAC denies this app's reads. */
    BLOCKED,

    /** The kernel exposes no thermal zones at all. */
    ABSENT,
}

/**
 * One kernel thermal zone. [rawType] is kept verbatim next to the prettified
 * [name] so a curious user can always see what the kernel actually called it.
 */
data class ThermalZone(
    val name: String,
    val rawType: String,
    val temperatureC: Double,
)

data class ThermalSnapshot(
    val status: ThermalStatus,
    /**
     * Normalized thermal headroom forecast (API 30+): 1.0 means throttling is
     * imminent. Null where the device does not implement it — most do not.
     */
    val headroom: Float?,
    /** Plausible zones only, hottest first. */
    val zones: List<ThermalZone>,
    val zoneAccess: ZoneAccess,
    /** Zones discovered but omitted this sample because their value was implausible. */
    val hiddenZoneCount: Int,
) {
    val hottest: ThermalZone? get() = zones.firstOrNull()
}

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

/**
 * One performance-log row. A null section means that sampler failed or is
 * unsupported on this device — logged as empty CSV cells, never fabricated.
 */
data class LogSample(
    val timeMillis: Long,
    val cpu: CpuSnapshot?,
    val memory: MemorySnapshot?,
    val battery: BatterySnapshot?,
    val network: NetworkSnapshot?,
    /** Readouts pause while the screen is off; the log records it instead. */
    val screenOn: Boolean,
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

/** [UNKNOWN]: usage access is missing, so recency is unknowable — rendered as
 *  a dash, never as a fabricated "Not running" claim. Declaration order is the
 *  status-column sort order. */
enum class ProcStatus { ACTIVE, RECENT, CACHED, INACTIVE, UNKNOWN }

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
