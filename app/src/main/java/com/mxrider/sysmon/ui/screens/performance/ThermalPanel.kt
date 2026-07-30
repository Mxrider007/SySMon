package com.mxrider.sysmon.ui.screens.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.ChartHistory
import com.mxrider.sysmon.core.Format
import com.mxrider.sysmon.core.ThermalStatus
import com.mxrider.sysmon.core.ZoneAccess
import com.mxrider.sysmon.ui.components.StatTile
import com.mxrider.sysmon.ui.components.chart.PerfChart
import com.mxrider.sysmon.ui.theme.FluentTheme
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThermalPanel(
    state: PerfUiState,
    thermalHistory: ChartHistory,
    modifier: Modifier = Modifier,
) {
    val colors = FluentTheme.colors
    val typography = FluentTheme.typography
    val thermal = state.thermal
    val hottest = thermal?.hottest

    Column(modifier.fillMaxWidth()) {
        PanelHeader(
            title = stringResource(R.string.perf_tile_thermal),
            trailing = hottest?.let { "${it.name} • ${Format.celsius(it.temperatureC)}" },
        )
        Spacer(Modifier.height(12.dp))

        // Fixed 0-100 °C: the band every phone sensor lives in, so a hot device
        // visibly climbs instead of autoscale flattening every state into the
        // same-looking line.
        PerfChart(
            history = thermalHistory,
            windowPoints = state.windowPoints,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            maxValue = 100f,
            topLeftLabel = stringResource(R.string.perf_axis_temperature),
            topRightLabel = stringResource(R.string.perf_axis_100c),
            bottomLeftLabel = stringResource(R.string.perf_axis_60s),
            bottomRightLabel = stringResource(R.string.perf_axis_0),
            valueFormatter = { Format.celsius(it.toDouble()) },
            contentDescription = stringResource(
                R.string.perf_cd_thermal,
                hottest?.let { Format.celsius(it.temperatureC) } ?: DASH,
            ),
        )

        Spacer(Modifier.height(16.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatTile(
                label = stringResource(R.string.perf_thermal_status),
                value = stringResource(statusLabel(thermal?.status)),
            )
            // Headroom is optional in the HAL and most devices return nothing;
            // a dash says so rather than deriving a lookalike from the sensors.
            StatTile(
                label = stringResource(R.string.perf_thermal_headroom),
                value = thermal?.headroom?.let { String.format(Locale.US, "%.2f", it) } ?: DASH,
                secondary = stringResource(
                    if (thermal?.headroom != null) {
                        R.string.perf_thermal_headroom_note
                    } else {
                        R.string.perf_thermal_headroom_absent
                    },
                ),
            )
            StatTile(
                label = stringResource(R.string.perf_thermal_hottest),
                value = hottest?.let { Format.celsius(it.temperatureC) } ?: DASH,
                secondary = hottest?.name,
            )
            StatTile(
                label = stringResource(R.string.perf_thermal_sensors),
                value = thermal?.zones?.size?.toString() ?: DASH,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(statusExplanation(thermal?.status)),
            style = typography.body,
            color = colors.textSecondary,
        )

        if (thermal != null && thermal.zones.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            thermal.zones.forEach { zone ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = zone.name,
                        style = typography.body,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(190.dp),
                    )
                    // The kernel's own name, kept visible: prettified labels are
                    // a convenience, not a replacement for what the device said.
                    Text(
                        text = zone.rawType,
                        style = typography.caption,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = Format.celsius(zone.temperatureC),
                        style = typography.body,
                        color = colors.textPrimary,
                    )
                }
            }
            if (thermal.hiddenZoneCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.perf_thermal_hidden_note,
                        thermal.hiddenZoneCount,
                        thermal.hiddenZoneCount,
                    ),
                    style = typography.caption,
                    color = colors.textSecondary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.perf_thermal_note),
                style = typography.caption,
                color = colors.textSecondary,
            )
        } else if (thermal != null) {
            // Three different facts, three different messages. "Readable but
            // every value was junk" is NOT the same as "the OS denied us", and
            // claiming the latter would be its own small lie.
            val (title, body) = when {
                thermal.zoneAccess == ZoneAccess.ABSENT ->
                    R.string.perf_thermal_absent_title to R.string.perf_thermal_absent_body
                thermal.zoneAccess == ZoneAccess.OK ->
                    R.string.perf_thermal_implausible_title to
                        R.string.perf_thermal_implausible_body
                else ->
                    R.string.perf_thermal_blocked_title to R.string.perf_thermal_blocked_body
            }
            Spacer(Modifier.height(16.dp))
            InfoStrip(text = stringResource(title))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(body),
                style = typography.body,
                color = colors.textSecondary,
            )
        }
    }
}

internal fun statusLabel(status: ThermalStatus?): Int = when (status) {
    ThermalStatus.NONE -> R.string.perf_thermal_status_none
    ThermalStatus.LIGHT -> R.string.perf_thermal_status_light
    ThermalStatus.MODERATE -> R.string.perf_thermal_status_moderate
    ThermalStatus.SEVERE -> R.string.perf_thermal_status_severe
    ThermalStatus.CRITICAL -> R.string.perf_thermal_status_critical
    ThermalStatus.EMERGENCY -> R.string.perf_thermal_status_emergency
    ThermalStatus.SHUTDOWN -> R.string.perf_thermal_status_shutdown
    else -> R.string.perf_thermal_status_unknown
}

private fun statusExplanation(status: ThermalStatus?): Int = when (status) {
    ThermalStatus.NONE -> R.string.perf_thermal_status_explain_none
    ThermalStatus.LIGHT -> R.string.perf_thermal_status_explain_light
    ThermalStatus.MODERATE -> R.string.perf_thermal_status_explain_moderate
    ThermalStatus.SEVERE -> R.string.perf_thermal_status_explain_severe
    ThermalStatus.CRITICAL -> R.string.perf_thermal_status_explain_critical
    ThermalStatus.EMERGENCY -> R.string.perf_thermal_status_explain_emergency
    ThermalStatus.SHUTDOWN -> R.string.perf_thermal_status_explain_shutdown
    else -> R.string.perf_thermal_status_explain_unknown
}
