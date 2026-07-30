package com.mxrider.sysmon.ui.screens.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.ChartHistory
import com.mxrider.sysmon.core.Format
import com.mxrider.sysmon.ui.components.StatTile
import com.mxrider.sysmon.ui.components.chart.PerfChart
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BatteryPanel(
    state: PerfUiState,
    powerHistory: ChartHistory,
    tempHistory: ChartHistory,
    modifier: Modifier = Modifier,
) {
    val bat = state.battery
    val levelText = bat?.let { "${it.levelPercent}%" } ?: DASH

    Column(modifier.fillMaxWidth()) {
        // Temperature rides in the header: the most-demanded metric in the
        // category, so it should be visible without scrolling to the tiles.
        PanelHeader(
            title = stringResource(R.string.perf_tile_battery),
            trailing = bat?.let { b ->
                listOfNotNull(b.status, b.temperatureC?.let { Format.celsius(it) })
                    .joinToString(" • ")
            },
        )
        Spacer(Modifier.height(12.dp))

        // Power draw, not level: over a 60-second window the level is a flat
        // line on every healthy phone, while draw is the signal people open
        // this panel for. Magnitude only — the signed Power stat below keeps
        // the charge/discharge direction.
        PerfChart(
            history = powerHistory,
            windowPoints = state.windowPoints,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            autoScale = true,
            minAutoScale = 1f, // watts, not bytes: 1 W floor keeps idle honest
            ceilingFormatter = { Format.watts(it.toDouble()) },
            topLeftLabel = stringResource(R.string.perf_axis_power),
            bottomLeftLabel = stringResource(R.string.perf_axis_60s),
            bottomRightLabel = stringResource(R.string.perf_axis_0),
            valueFormatter = { Format.watts(it.toDouble()) },
            contentDescription = stringResource(
                R.string.perf_cd_battery_power,
                bat?.powerWatts?.let { Format.watts(it) } ?: DASH,
            ),
        )
        Spacer(Modifier.height(12.dp))
        // Fixed 0-50 °C so a healthy ~30 °C reads mid-chart and heat shows as
        // a real climb; autoscale would zoom noise into drama.
        PerfChart(
            history = tempHistory,
            windowPoints = state.windowPoints,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            maxValue = 50f,
            topLeftLabel = stringResource(R.string.perf_axis_temperature),
            topRightLabel = stringResource(R.string.perf_axis_50c),
            bottomLeftLabel = stringResource(R.string.perf_axis_60s),
            bottomRightLabel = stringResource(R.string.perf_axis_0),
            valueFormatter = { Format.celsius(it.toDouble()) },
            contentDescription = stringResource(
                R.string.perf_cd_battery_temp,
                bat?.temperatureC?.let { Format.celsius(it) } ?: DASH,
            ),
        )

        Spacer(Modifier.height(16.dp))
        // Signed power: "+" while charging, "-" while discharging; dash when
        // the device does not report current/voltage — never fabricated.
        val powerText = bat?.powerWatts?.let { w ->
            if (w >= 0) "+${Format.watts(w)}" else Format.watts(w)
        } ?: DASH
        val currentText = bat?.currentMicroA?.let {
            String.format(Locale.US, "%+d mA", it / 1000)
        } ?: DASH
        val voltageText = bat?.voltageMilliV?.let {
            String.format(Locale.US, "%.2f V", it / 1000.0)
        } ?: DASH
        val tempText = bat?.temperatureC?.let { Format.celsius(it) } ?: DASH

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatTile(
                label = stringResource(R.string.perf_battery_level),
                value = levelText,
            )
            StatTile(
                label = stringResource(R.string.perf_battery_status),
                value = bat?.status ?: DASH,
                secondary = bat?.plugType,
            )
            StatTile(
                label = stringResource(R.string.perf_battery_power),
                value = powerText,
            )
            StatTile(
                label = stringResource(R.string.perf_battery_current),
                value = currentText,
            )
            StatTile(
                label = stringResource(R.string.perf_battery_voltage),
                value = voltageText,
            )
            StatTile(
                label = stringResource(R.string.perf_battery_temperature),
                value = tempText,
            )
            StatTile(
                label = stringResource(R.string.perf_battery_health),
                value = bat?.health ?: DASH,
            )
            StatTile(
                label = stringResource(R.string.perf_battery_technology),
                value = bat?.technology ?: DASH,
            )
            StatTile(
                label = stringResource(R.string.perf_battery_cycles),
                value = bat?.cycleCount?.toString() ?: DASH,
            )
            // Wear estimate: fuel-gauge charge counter extrapolated by level,
            // against the ROM's design figure where readable. Every part is
            // an estimate and says so; missing parts degrade to a dash, never
            // to an invented number.
            val estFull = bat?.estimatedFullCapacityMAh()
            val design = bat?.designCapacityMAh
            StatTile(
                label = stringResource(R.string.perf_battery_capacity),
                value = estFull?.let {
                    String.format(Locale.US, "~%,d mAh", it.toInt())
                } ?: DASH,
                secondary = when {
                    estFull != null && design != null -> stringResource(
                        R.string.perf_battery_capacity_of_design,
                        (estFull * 100.0 / design).toInt(),
                        String.format(Locale.US, "%,d", design.toInt()),
                    )
                    estFull != null -> stringResource(R.string.perf_battery_capacity_basis)
                    else -> null
                },
            )
        }
    }
}
