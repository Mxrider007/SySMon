package com.mxrider.sysmon.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mxrider.sysmon.R

/**
 * Display text for the English tokens the battery and network snapshots
 * carry. The tokens stay English in the data layer because the CSV log
 * records them verbatim, and a log must not change vocabulary with the UI
 * language; screens and notifications translate them here. A token without
 * a translation (a carrier name, for one) is shown as is.
 */
object TokenLabels {

    fun batteryStatus(token: String): Int? = when (token) {
        "Charging" -> R.string.perf_battery_status_charging
        "Discharging" -> R.string.perf_battery_status_discharging
        "Full" -> R.string.perf_battery_status_full
        "Not charging" -> R.string.perf_battery_status_not_charging
        "Unknown" -> R.string.perf_battery_unknown
        else -> null
    }

    fun batteryHealth(token: String): Int? = when (token) {
        "Good" -> R.string.perf_battery_health_good
        "Overheat" -> R.string.perf_battery_health_overheat
        "Dead" -> R.string.perf_battery_health_dead
        "Over voltage" -> R.string.perf_battery_health_over_voltage
        "Failure" -> R.string.perf_battery_health_failure
        "Cold" -> R.string.perf_battery_health_cold
        "Unknown" -> R.string.perf_battery_unknown
        else -> null
    }

    fun plugType(token: String): Int? = when (token) {
        "Wireless" -> R.string.perf_battery_plug_wireless
        "Dock" -> R.string.perf_battery_plug_dock
        else -> null // "AC" and "USB" read the same everywhere.
    }

    fun networkName(token: String): Int? = when (token) {
        "Mobile" -> R.string.perf_transport_cellular
        "Other" -> R.string.perf_transport_other
        else -> null // Wi-Fi / Ethernet / VPN, or the carrier's own name.
    }

    @Composable
    fun localized(token: String?, map: (String) -> Int?): String? =
        token?.let { t -> map(t)?.let { stringResource(it) } ?: t }

    fun localized(context: Context, token: String?, map: (String) -> Int?): String? =
        token?.let { t -> map(t)?.let { context.getString(it) } ?: t }
}
