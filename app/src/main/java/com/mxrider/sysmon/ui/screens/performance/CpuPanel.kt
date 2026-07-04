package com.mxrider.sysmon.ui.screens.performance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.ChartHistory
import com.mxrider.sysmon.core.CpuSource
import com.mxrider.sysmon.core.Format
import com.mxrider.sysmon.ui.components.EmptyState
import com.mxrider.sysmon.ui.components.FluentDialog
import com.mxrider.sysmon.ui.components.FluentStandardButton
import com.mxrider.sysmon.ui.components.StatTile
import com.mxrider.sysmon.ui.components.chart.PerfChart
import com.mxrider.sysmon.ui.theme.FluentTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CpuPanel(
    state: PerfUiState,
    history: ChartHistory,
    coreHistories: List<ChartHistory>,
    modifier: Modifier = Modifier,
) {
    val colors = FluentTheme.colors
    val typography = FluentTheme.typography
    val cpu = state.cpu
    val info = state.cpuStatic
    var showCores by rememberSaveable { mutableStateOf(false) }
    var showEstimateWhy by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        PanelHeader(
            title = stringResource(R.string.perf_tile_cpu),
            trailing = info?.socModel,
        )
        Spacer(Modifier.height(12.dp))

        if (cpu != null && cpu.source == CpuSource.UNAVAILABLE) {
            // Honesty rule: never draw a fabricated utilization series.
            EmptyState(
                title = stringResource(R.string.perf_cpu_unavailable_title),
                body = stringResource(R.string.perf_cpu_unavailable_body),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                icon = Icons.Filled.Memory,
            )
        } else {
            val latestText = if (cpu != null) Format.percent(cpu.utilization) else DASH
            PerfChart(
                history = history,
                windowPoints = state.windowPoints,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                maxValue = 1f,
                topLeftLabel = stringResource(R.string.perf_axis_utilization),
                topRightLabel = stringResource(R.string.perf_axis_100),
                bottomLeftLabel = stringResource(R.string.perf_axis_60s),
                bottomRightLabel = stringResource(R.string.perf_axis_0),
                valueFormatter = { Format.percent(it) },
                contentDescription = stringResource(R.string.perf_cd_cpu, latestText),
            )
            if (cpu?.source == CpuSource.FREQ_ESTIMATE) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.perf_cpu_estimated_note),
                    style = typography.caption,
                    color = colors.textSecondary,
                )
                // One-tap explainer: the category's trained skeptics punish
                // unexplained estimates; the label is honest, this is the why.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button) { showEstimateWhy = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.perf_cpu_estimate_why),
                        style = typography.caption,
                        color = colors.accent,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            val speedKhz = if (cpu != null) averageCurFreqKhz(cpu) else 0L
            val sourceText = when (cpu?.source) {
                CpuSource.PROC_STAT -> stringResource(R.string.perf_source_proc_stat)
                CpuSource.FREQ_ESTIMATE -> stringResource(R.string.perf_source_freq_estimate)
                CpuSource.UNAVAILABLE -> stringResource(R.string.perf_source_unavailable)
                null -> DASH
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatTile(
                    label = stringResource(R.string.perf_stat_utilization),
                    value = latestText,
                )
                StatTile(
                    label = stringResource(R.string.perf_stat_speed),
                    value = Format.freqGhz(speedKhz),
                )
                StatTile(
                    label = stringResource(R.string.perf_stat_uptime),
                    value = if (cpu != null) Format.uptime(cpu.uptimeMillis) else DASH,
                )
                StatTile(
                    label = stringResource(R.string.perf_stat_source),
                    value = sourceText,
                )
            }

            Spacer(Modifier.height(16.dp))
            FluentStandardButton(
                text = stringResource(R.string.perf_logical_processors),
                onClick = { showCores = !showCores },
            )
            if (showCores) {
                Spacer(Modifier.height(12.dp))
                if (coreHistories.isEmpty()) {
                    Text(
                        text = stringResource(R.string.label_loading),
                        style = typography.body,
                        color = colors.textSecondary,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        coreHistories.chunked(2).forEachIndexed { rowIndex, rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                rowItems.forEachIndexed { colIndex, coreHistory ->
                                    val coreIndex = rowIndex * 2 + colIndex
                                    val coreLatest = if (coreHistory.latest.isFinite()) {
                                        Format.percent(coreHistory.latest)
                                    } else {
                                        DASH
                                    }
                                    Column(Modifier.weight(1f)) {
                                        PerfChart(
                                            history = coreHistory,
                                            windowPoints = state.windowPoints,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(90.dp),
                                            maxValue = 1f,
                                            valueFormatter = { Format.percent(it) },
                                            contentDescription = stringResource(
                                                R.string.perf_cd_core, coreIndex, coreLatest,
                                            ),
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = stringResource(R.string.perf_core_n, coreIndex),
                                            style = typography.caption,
                                            color = colors.textSecondary,
                                        )
                                    }
                                }
                                if (rowItems.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (info != null) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.perf_specs),
                style = typography.bodyStrong,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            SpecRow(stringResource(R.string.perf_spec_soc), info.socModel)
            if (info.hardware.isNotBlank() && info.hardware != info.socModel) {
                SpecRow(stringResource(R.string.perf_spec_hardware), info.hardware)
            }
            SpecRow(stringResource(R.string.perf_spec_cores), info.coreCount.toString())
            SpecRow(stringResource(R.string.perf_spec_min_speed), Format.freqGhz(info.minFreqKhz))
            SpecRow(stringResource(R.string.perf_spec_max_speed), Format.freqGhz(info.maxFreqKhz))
            SpecRow(stringResource(R.string.perf_spec_abi), info.abi)
            SpecRow(
                stringResource(R.string.perf_spec_64bit),
                stringResource(if (info.is64Bit) R.string.perf_yes else R.string.perf_no),
            )
        }
    }

    if (showEstimateWhy) {
        FluentDialog(
            title = stringResource(R.string.perf_cpu_estimate_why_title),
            onDismiss = { showEstimateWhy = false },
            confirmText = stringResource(R.string.action_ok),
            onConfirm = { showEstimateWhy = false },
            body = stringResource(R.string.perf_cpu_estimate_why_body),
            showCancel = false,
        )
    }
}
