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
     */
    private fun readHeadroom(): Float? {
        if (Build.VERSION.SDK_INT < 30) return null
        val pm = powerManager ?: return null
        return try {
            val value = pm.getThermalHeadroom(0)
            if (value.isNaN() || value <= 0f) null else value
        } catch (_: Exception) {
            null
        }
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

        /** Physically sane band for a phone sensor, in °C. */
        const val MIN_C = 10.0
        const val MAX_C = 120.0

        /**
         * Kernels disagree about units *within one device*: this test phone
         * reports `pcm_therm` in milli°C (29350), `tsens_tz_sensor0` in
         * deci°C (304) and `quiet_therm` in plain °C (23). Dividing everything
         * by 1000 — the common shortcut — would render "0.3 °C".
         *
         * The four candidate scales map the sane band to disjoint raw ranges
         * ([10,120], [100,1200], [1000,12000], [10000,120000]), so at most one
         * can match and there is nothing to guess between. A value that fits
         * none is a broken sensor and is reported as hidden, never displayed.
         */
        fun plausibleCelsius(raw: Long): Double? {
            for (scale in longArrayOf(1L, 10L, 100L, 1000L)) {
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
        val CPU_INDEX = Regex("""^cpu[-_]?(\d+)""")
    }

    private data class ZoneHandle(
        val tempFile: File,
        val rawType: String,
        val prettyName: String,
    )
}
