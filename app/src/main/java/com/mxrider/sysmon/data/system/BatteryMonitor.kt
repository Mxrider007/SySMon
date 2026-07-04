package com.mxrider.sysmon.data.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.mxrider.sysmon.core.BatteryMonitor
import com.mxrider.sysmon.core.BatterySnapshot
import kotlin.math.abs

/**
 * Battery sampler combining [BatteryManager] properties with the sticky
 * [Intent.ACTION_BATTERY_CHANGED] broadcast. Handles the usual OEM quirks:
 * current reported in mA instead of µA, inverted current sign, and
 * `Integer.MIN_VALUE` for unsupported properties.
 */
class DefaultBatteryMonitor(context: Context) : BatteryMonitor {

    private val appContext = context.applicationContext
    private val batteryManager =
        appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    override suspend fun sample(): BatterySnapshot {
        val intent = stickyBatteryIntent()

        val statusCode = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val status = statusString(statusCode)
        val isCharging = statusCode == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusCode == BatteryManager.BATTERY_STATUS_FULL

        val level = levelPercent(intent)
        // A known set of OEM kernels report EXTRA_VOLTAGE in µV (~3,850,000);
        // anything over a 20 V plausibility ceiling gets scaled back to mV.
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }
            ?.let { if (it > 20_000) it / 1000 else it }
        val current = normalizedCurrentMicroA(statusCode)
        val power = if (voltage != null && current != null) {
            (voltage / 1000.0) * (current / 1_000_000.0)
        } else {
            null
        }
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10.0 }

        return BatterySnapshot(
            levelPercent = level,
            isCharging = isCharging,
            plugType = plugType(intent),
            currentMicroA = current,
            voltageMilliV = voltage,
            powerWatts = power,
            temperatureC = temperature,
            health = healthString(intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1),
            technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            cycleCount = cycleCount(intent),
            status = status,
        )
    }

    private fun stickyBatteryIntent(): Intent? = try {
        // Null receiver + sticky broadcast: returns the last broadcast intent.
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (_: Exception) {
        null
    }

    private fun levelPercent(intent: Intent?): Int {
        // 0 excluded: some older OEM HALs report 0 for "unsupported" instead
        // of Integer.MIN_VALUE; a genuinely empty battery still reads 0 via
        // the intent fallback below.
        val capacity = intProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (capacity != null && capacity in 1..100) return capacity
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) return (level * 100 / scale).coerceIn(0, 100)
        }
        return 0
    }

    /**
     * Reads BATTERY_PROPERTY_CURRENT_NOW (µA) applying two OEM quirks:
     * values under 10 000 in magnitude are actually mA (scale by 1000), and
     * some devices invert the sign — normalize using the charging status
     * (charging => positive, discharging => negative).
     */
    private fun normalizedCurrentMicroA(statusCode: Int): Long? {
        val raw = intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: return null
        var microA = raw.toLong()
        if (abs(microA) < 10_000L) microA *= 1000L
        when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> if (microA < 0) microA = -microA
            BatteryManager.BATTERY_STATUS_DISCHARGING -> if (microA > 0) microA = -microA
        }
        return microA
    }

    /** Integer.MIN_VALUE from getIntProperty means unsupported -> null. */
    private fun intProperty(property: Int): Int? {
        val v = try {
            batteryManager?.getIntProperty(property) ?: return null
        } catch (_: Exception) {
            return null
        }
        return if (v == Int.MIN_VALUE) null else v
    }

    private fun cycleCount(intent: Intent?): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val v = intent?.getIntExtra(EXTRA_CYCLE_COUNT, -1) ?: -1
        return if (v >= 0) v else null
    }

    private fun plugType(intent: Intent?): String? =
        when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            BATTERY_PLUGGED_DOCK -> "Dock"
            else -> null
        }

    private fun statusString(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }

    private fun healthString(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Unknown"
    }

    private companion object {
        /** BatteryManager.EXTRA_CYCLE_COUNT, API 34+. */
        const val EXTRA_CYCLE_COUNT = "android.os.extra.CYCLE_COUNT"

        /** BatteryManager.BATTERY_PLUGGED_DOCK, constant value 8 (API 33+). */
        const val BATTERY_PLUGGED_DOCK = 8
    }
}
