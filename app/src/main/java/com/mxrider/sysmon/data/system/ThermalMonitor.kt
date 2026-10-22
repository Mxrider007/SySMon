package com.mxrider.sysmon.data.system

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.mxrider.sysmon.core.ThermalMonitor
import com.mxrider.sysmon.core.ThermalSnapshot
import com.mxrider.sysmon.core.ThermalStatus
import com.mxrider.sysmon.core.ThermalZone
import com.mxrider.sysmon.core.ZoneAccess
import java.io.File
import java.util.Locale

/**
 * Thermal readings from two independent sources, neither of which is fabricated
 * when missing:
 *
 * 1. **PowerManager** — the OS's own throttling status (API 29+) and headroom
 *    forecast (API 30+). Always permitted, so it anchors the panel everywhere.
 * 2. **`/sys/class/thermal`** — per-sensor temperatures. World-readable by DAC
 *    on many devices but frequently denied to `untrusted_app` by SELinux, which
 *    is reported honestly as [ZoneAccess.BLOCKED] rather than hidden.
 */
class DefaultThermalMonitor(context: Context) : ThermalMonitor {

    private val powerManager =
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /** Zone dirs never appear or vanish at runtime, so discovery runs once. */
    private var discovered: List<ZoneHandle>? = null
    private var access: ZoneAccess = ZoneAccess.ABSENT

    /** Last headroom reading and when it was taken ([android.os.SystemClock.elapsedRealtime]). */
    private var headroomCache: Float? = null

    // Not Long.MIN_VALUE: `now - MIN_VALUE` overflows to a negative number,
    // which would satisfy the rate-limit check forever and never read.
    private var headroomReadAt: Long = -HEADROOM_MIN_INTERVAL_MS

    override suspend fun sample(): ThermalSnapshot {
        val zones = discovered ?: discover().also { discovered = it }

        val readings = ArrayList<ThermalZone>(zones.size)
        var hidden = 0
        var anyReadable = false
        for (zone in zones) {
            val raw = readLong(zone.tempFile)
            if (raw == null) continue
            anyReadable = true
            val celsius = plausibleCelsius(raw)
            if (celsius == null) {
                hidden++
            } else {
                readings += ThermalZone(zone.prettyName, zone.rawType, celsius)
            }
        }
        readings.sortByDescending { it.temperatureC }

        // Zones we could list but never read is the blocked case. Zones we read
        // successfully stay OK even when every value was junk — that is a broken
        // sensor, not a denied one, and the panel says so in different words.
        val zoneAccess = when {
            zones.isEmpty() -> access
            anyReadable -> ZoneAccess.OK
            else -> ZoneAccess.BLOCKED
        }

        return ThermalSnapshot(
            status = readStatus(),
            headroom = readHeadroom(),
            zones = readings,
            zoneAccess = zoneAccess,
            hiddenZoneCount = hidden,
        )
    }

    private fun readStatus(): ThermalStatus {
        if (Build.VERSION.SDK_INT < 29) return ThermalStatus.UNKNOWN
        val pm = powerManager ?: return ThermalStatus.UNKNOWN
        return try {
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
                PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
                else -> ThermalStatus.UNKNOWN
            }
        } catch (_: Exception) {
            ThermalStatus.UNKNOWN
        }
    }

    /**
     * Most devices return NaN here (the HAL hook is optional), and some throw.
     * Both mean "not reported" — the panel dashes it rather than guessing from
     * the sensor values, which would be a different measurement entirely.
     *
     * The platform also rate-limits this API: called more than about once per
     * second it returns NaN, so at the HIGH update speed (500 ms ticks) a
     * device that DOES report headroom would flicker value/dash at 2 Hz.
     * Reads are therefore spaced >= 1 s apart and the last reading is reused
     * in between — a <= 1 s-old forecast, not a fabricated one.
     */
    private fun readHeadroom(): Float? {
        if (Build.VERSION.SDK_INT < 30) return null
        val pm = powerManager ?: return null
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - headroomReadAt < HEADROOM_MIN_INTERVAL_MS) return headroomCache
        headroomReadAt = now
        headroomCache = try {
            val value = pm.getThermalHeadroom(0)
            if (value.isNaN() || value <= 0f) null else value
        } catch (_: Exception) {
            null
        }
        return headroomCache
    }

    private fun discover(): List<ZoneHandle> {
        val root = File(THERMAL_ROOT)
        val dirs = try {
            root.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
        } catch (_: Exception) {
            null
        }
        if (dirs == null || dirs.isEmpty()) {
            access = if (root.exists()) ZoneAccess.BLOCKED else ZoneAccess.ABSENT
            return emptyList()
        }
        val handles = dirs
            .sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
            .take(MAX_ZONES)
            .mapNotNull { dir ->
                val temp = File(dir, "temp")
                if (!temp.canRead()) return@mapNotNull null
                val rawType = readText(File(dir, "type")) ?: dir.name
                ZoneHandle(
                    tempFile = temp,
                    rawType = rawType,
                    prettyName = prettify(rawType),
                )
            }
        access = if (handles.isEmpty()) ZoneAccess.BLOCKED else ZoneAccess.OK
        return handles
    }

    private fun readText(file: File): String? = try {
        if (file.canRead()) file.readText().trim().takeIf { it.isNotEmpty() } else null
    } catch (_: Exception) {
        null
    }

    private fun readLong(file: File): Long? = try {
        file.readText().trim().toLongOrNull()
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val THERMAL_ROOT = "/sys/class/thermal"

        /** Plenty for any real device; keeps a pathological kernel from stalling a tick. */
        const val MAX_ZONES = 48

        /** The platform returns NaN for headroom queries spaced closer than ~1 s. */
        const val HEADROOM_MIN_INTERVAL_MS = 1_000L

        /** Physically sane band for a phone sensor, in °C. */
        const val MIN_C = 10.0
        const val MAX_C = 120.0

        /**
         * Kernels disagree about units *within one device*: this test phone
         * reports `pcm_therm` in milli°C (29350), `tsens_tz_sensor0` in
         * deci°C (304) and `quiet_therm` in plain °C (23). Dividing everything
         * by 1000 — the common shortcut — would render "0.3 °C".
         *
         * The candidate scales map the sane band to raw ranges that are almost
         * disjoint but overlap at the decade edges (raw 100–120 is a valid
         * plain-°C reading AND a valid deci-°C one). There is deliberately no
         * centi-°C candidate: no kernel uses it, and it would turn a genuinely
         * cold milli-°C reading (raw 5000 = 5 °C) into a fabricated 50 °C
         * instead of hiding it. For those ambiguous
         * values the larger divisor wins: sysfs documents millidegrees as the
         * standard unit, so the more standard interpretation is the better
         * prior — and it is also the calmer one, which matters when the
         * alternative is displaying a spurious near-shutdown temperature.
         * A value fitting no scale is a broken sensor and is reported as
         * hidden, never displayed.
         */
        fun plausibleCelsius(raw: Long): Double? {
            for (scale in longArrayOf(1000L, 10L, 1L)) {
                val c = raw.toDouble() / scale
                if (c in MIN_C..MAX_C) return c
            }
            return null
        }

        /**
         * Kernel zone names are cryptic ("tsens_tz_sensor7", "xo_therm"). Map
         * the families we can identify and pass anything else through, so an
         * unknown sensor still shows up under its real name.
         */
        fun prettify(rawType: String): String {
            val t = rawType.lowercase(Locale.US)
            SENSOR_INDEX.find(t)?.let { match ->
                val n = match.groupValues[1]
                return "SoC sensor $n"
            }
            // Qualcomm names per-core zones cpu-<cluster>-<core>-usr; the
            // single-index form below would label every core of a cluster
            // with the cluster number.
            CPU_CLUSTER_CORE.find(t)?.let { match ->
                return "CPU cluster ${match.groupValues[1]} core ${match.groupValues[2]}"
            }
            CPU_INDEX.find(t)?.let { match ->
                return "CPU ${match.groupValues[1]}"
            }
            return when {
                t.startsWith("gpu") || t.contains("kgsl") -> "GPU"
                t.contains("batt") -> "Battery"
                t.contains("skin") || t.startsWith("quiet") -> "Skin"
                t.contains("chg") || t.contains("charger") || t.contains("usb") -> "Charger"
                t.contains("modem") || t.startsWith("mdm") -> "Modem"
                t.startsWith("pa_") || t.contains("rfpa") -> "Antenna"
                t.startsWith("xo_") -> "Crystal oscillator"
                t.contains("wlan") || t.contains("wifi") -> "Wi-Fi"
                t.contains("camera") || t.startsWith("cam") -> "Camera"
                t.contains("mem") || t.contains("ddr") -> "Memory"
                t.startsWith("pcm") -> "PCM"
                t.contains("display") || t.contains("lcd") -> "Display"
                else -> rawType
            }
        }

        val SENSOR_INDEX = Regex("""tsens_tz_sensor(\d+)""")
        val CPU_CLUSTER_CORE = Regex("""^cpu[-_](\d+)[-_](\d+)""")
        val CPU_INDEX = Regex("""^cpu[-_]?(\d+)""")
    }

    private data class ZoneHandle(
        val tempFile: File,
        val rawType: String,
        val prettyName: String,
    )
}
