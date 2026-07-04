package com.mxrider.sysmon.ui.screens.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.CpuSource
import com.mxrider.sysmon.core.Format
import com.mxrider.sysmon.ui.components.TitleBar
import com.mxrider.sysmon.ui.components.chart.MiniChartTile
import com.mxrider.sysmon.ui.theme.FluentMetrics

/** Sidebar entries of the Performance page, in Win11 Task Manager order. */
internal enum class PerfPanel { CPU, MEMORY, NETWORK, STORAGE, BATTERY, GPU }

private val TILE_COLUMN_WIDTH = 230.dp
private val COMPACT_BREAKPOINT = 600.dp

@Composable
fun PerformanceScreen(modifier: Modifier = Modifier) {
    val vm: PerformanceViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(PerfPanel.CPU) }

    LaunchedEffect(selected) {
        if (selected == PerfPanel.GPU) vm.loadGpuInfo()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(FluentMetrics.pagePadding),
    ) {
        TitleBar(title = stringResource(R.string.perf_title))
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth < COMPACT_BREAKPOINT) {
                // Compact: tiles become a horizontal strip above the panel.
                Column(Modifier.fillMaxSize()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(PerfPanel.entries, key = { it.name }) { panel ->
                            PerfTile(
                                panel = panel,
                                state = state,
                                vm = vm,
                                selected = selected == panel,
                                onClick = { selected = panel },
                                modifier = Modifier.width(TILE_COLUMN_WIDTH),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        PanelContent(selected, state, vm)
                    }
                }
            } else {
                // Wide: Win11 split view — tile rail on the left, panel right.
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .width(TILE_COLUMN_WIDTH)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PerfPanel.entries.forEach { panel ->
                            PerfTile(
                                panel = panel,
                                state = state,
                                vm = vm,
                                selected = selected == panel,
                                onClick = { selected = panel },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        PanelContent(selected, state, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelContent(panel: PerfPanel, state: PerfUiState, vm: PerformanceViewModel) {
    when (panel) {
        PerfPanel.CPU -> CpuPanel(state, vm.cpuHistory, vm.coreHistories)
        PerfPanel.MEMORY -> MemoryPanel(state, vm.memoryHistory)
        PerfPanel.NETWORK -> NetworkPanel(state, vm.netRxHistory, vm.netTxHistory)
        PerfPanel.STORAGE -> StoragePanel(state)
        PerfPanel.BATTERY -> BatteryPanel(state, vm.batteryHistory)
        PerfPanel.GPU -> GpuPanel(state)
    }
}

@Composable
private fun PerfTile(
    panel: PerfPanel,
    state: PerfUiState,
    vm: PerformanceViewModel,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (panel) {
        PerfPanel.CPU -> {
            val cpu = state.cpu
            val value = when {
                cpu == null -> DASH
                cpu.source == CpuSource.UNAVAILABLE -> stringResource(R.string.label_unavailable)
                else -> Format.percent(cpu.utilization)
            }
            val speedKhz = cpu?.let { averageCurFreqKhz(it) } ?: 0L
            MiniChartTile(
                title = stringResource(R.string.perf_tile_cpu),
                value = value,
                history = vm.cpuHistory,
                windowPoints = state.windowPoints,
                selected = selected,
                onClick = onClick,
                modifier = modifier,
                secondaryValue = if (speedKhz > 0) Format.freqGhz(speedKhz) else null,
            )
        }
        PerfPanel.MEMORY -> {
            val mem = state.memory
            val value = if (mem != null && mem.totalBytes > 0) {
                stringResource(
                    R.string.perf_mem_tile_value,
                    Format.bytes(mem.usedBytes),
                    Format.bytes(mem.totalBytes),
                    Format.percent((mem.usedBytes.toFloat() / mem.totalBytes).coerceIn(0f, 1f)),
                )
            } else {
                DASH
            }
            MiniChartTile(
                title = stringResource(R.string.perf_tile_memory),
                value = value,
                history = vm.memoryHistory,
                windowPoints = state.windowPoints,
                selected = selected,
                onClick = onClick,
                modifier = modifier,
            )
        }
        PerfPanel.NETWORK -> {
            val net = state.network
            MiniChartTile(
                title = stringResource(R.string.perf_tile_network),
                value = net?.let {
                    stringResource(R.string.perf_net_rx_short, Format.bitsPerSec(it.rxBytesPerSec))
                } ?: DASH,
                history = vm.netRxHistory,
                windowPoints = state.windowPoints,
                selected = selected,
                onClick = onClick,
                modifier = modifier,
                secondaryValue = net?.let {
                    stringResource(R.string.perf_net_tx_short, Format.bitsPerSec(it.txBytesPerSec))
                },
            )
        }
        PerfPanel.STORAGE -> {
            val fraction = storageUsedFraction(state.storage)
            MiniChartTile(
                title = stringResource(R.string.perf_tile_storage),
                value = fraction?.let {
                    stringResource(R.string.perf_storage_used_fmt, Format.percent(it))
                } ?: DASH,
                history = vm.storageHistory,
                windowPoints = state.windowPoints,
                selected = selected,
                onClick = onClick,
                modifier = modifier,
            )
        }
        PerfPanel.BATTERY -> {
            val bat = state.battery
            MiniChartTile(
                title = stringResource(R.string.perf_tile_battery),
                value = bat?.let { "${it.levelPercent}%" } ?: DASH,
                history = vm.batteryHistory,
                windowPoints = state.windowPoints,
                selected = selected,
                onClick = onClick,
                modifier = modifier,
                secondaryValue = bat?.let { b ->
                    listOfNotNull(b.status, b.temperatureC?.let { Format.celsius(it) })
                        .joinToString(" • ")
                },
            )
        }
        PerfPanel.GPU -> {
            MiniChartTile(
                title = stringResource(R.string.perf_tile_gpu),
                value = stringResource(R.string.perf_gpu_info_value),
                history = vm.gpuHistory,
                windowPoints = state.windowPoints,
                selected = selected,
                onClick = onClick,
                modifier = modifier,
            )
        }
    }
}
