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
import com.mxrider.sysmon.ui.components.chart.BarSegment
import com.mxrider.sysmon.ui.components.chart.CompositionBar
import com.mxrider.sysmon.ui.components.chart.PerfChart
import com.mxrider.sysmon.ui.theme.FluentTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryPanel(
    state: PerfUiState,
    history: ChartHistory,
    modifier: Modifier = Modifier,
) {
    val colors = FluentTheme.colors
    val mem = state.memory
    val usedFraction = if (mem != null && mem.totalBytes > 0) {
        (mem.usedBytes.toFloat() / mem.totalBytes).coerceIn(0f, 1f)
    } else {
        null
    }

    Column(modifier.fillMaxWidth()) {
        PanelHeader(
            title = stringResource(R.string.perf_tile_memory),
            trailing = if (mem != null) Format.bytes(mem.totalBytes) else null,
        )
        Spacer(Modifier.height(12.dp))

        PerfChart(
            history = history,
            windowPoints = state.windowPoints,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            maxValue = 1f,
            topLeftLabel = stringResource(R.string.perf_axis_memory_usage),
            topRightLabel = if (mem != null) Format.bytes(mem.totalBytes) else null,
            bottomLeftLabel = stringResource(R.string.perf_axis_60s),
            bottomRightLabel = stringResource(R.string.perf_axis_0),
            valueFormatter = { Format.percent(it) },
            contentDescription = stringResource(
                R.string.perf_cd_memory,
                usedFraction?.let { Format.percent(it) } ?: DASH,
            ),
        )

        if (mem?.lowMemory == true) {
            Spacer(Modifier.height(8.dp))
            InfoStrip(text = stringResource(R.string.perf_mem_low_warning))
        }

        if (mem != null && usedFraction != null) {
            Spacer(Modifier.height(16.dp))
            val total = mem.totalBytes.toFloat()
            val inUseLabel = stringResource(R.string.perf_mem_seg_in_use, Format.bytes(mem.usedBytes))
            val cachedLabel = stringResource(
                R.string.perf_mem_seg_cached,
                if (mem.cachedBytes >= 0) Format.bytes(mem.cachedBytes) else DASH,
            )
            val segments = mutableListOf(
                BarSegment(usedFraction, colors.accent, inUseLabel),
            )
            if (mem.cachedBytes >= 0) {
                val cachedFraction = (mem.cachedBytes / total)
                    .coerceIn(0f, (1f - usedFraction).coerceAtLeast(0f))
                segments.add(BarSegment(cachedFraction, colors.accent.copy(alpha = 0.45f), cachedLabel))
            }
            CompositionBar(segments = segments, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(16.dp))
        val swapText = if (mem != null && mem.swapTotalBytes > 0) {
            stringResource(
                R.string.perf_ratio,
                Format.bytes((mem.swapTotalBytes - mem.swapFreeBytes).coerceAtLeast(0L)),
                Format.bytes(mem.swapTotalBytes),
            )
        } else {
            DASH
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatTile(
                label = stringResource(R.string.perf_mem_in_use),
                value = if (mem != null) Format.bytes(mem.usedBytes) else DASH,
            )
            StatTile(
                label = stringResource(R.string.perf_mem_available),
                value = if (mem != null) Format.bytes(mem.availBytes) else DASH,
            )
            StatTile(
                label = stringResource(R.string.perf_mem_cached),
                value = if (mem != null && mem.cachedBytes >= 0) Format.bytes(mem.cachedBytes) else DASH,
            )
            StatTile(
                label = stringResource(R.string.perf_mem_swap),
                value = swapText,
            )
            StatTile(
                label = stringResource(R.string.perf_mem_total),
                value = if (mem != null) Format.bytes(mem.totalBytes) else DASH,
            )
        }
    }
}
