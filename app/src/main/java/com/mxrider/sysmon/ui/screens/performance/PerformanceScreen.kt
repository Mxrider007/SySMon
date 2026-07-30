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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
internal enum class PerfPanel { CPU, MEMORY, NETWORK, STORAGE, BATTERY, THERMAL, GPU }

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

    // This activity handles config changes itself, so rotation never runs a
    // saved-state pass — when the compact/wide branches below swap, a plain
    // PanelContent call re-enters as a fresh subtree and open dialogs, the
    // core grid and the scroll position all silently reset. movableContentOf
    // moves the one panel subtree between the two slots with state intact.
    val panelBody = remember {
        movableContentOf { panel: PerfPanel, panelState: PerfUiState, bodyModifier: Modifier ->
            val scrollState = rememberScrollState()
            // One ScrollState lives inside this movable slot, so switching
            // panels would otherwise inherit the previous panel's offset
            // (Battery opening pre-scrolled to where CPU's specs were).
            LaunchedEffect(panel) { scrollState.scrollTo(0) }
            Column(bodyModifier.verticalScroll(scrollState)) {
                PanelContent(panel, panelState, vm)
            }
        }
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
                    val stripState = rememberLazyListState()
                    // Keep the selected tile fully in view: a tile tapped at
                    // the strip's edge is otherwise left half-clipped, and a
                    // selection restored after rotation or a tab return can
                    // sit entirely off-screen.
                    LaunchedEffect(selected) {
                        val index = selected.ordinal
                        val layout = stripState.layoutInfo
                        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                        when {
                            item == null -> stripState.animateScrollToItem(index)
                            item.offset < layout.viewportStartOffset ->
                                stripState.animateScrollBy(
                                    (item.offset - layout.viewportStartOffset).toFloat(),
                                )
                            item.offset + item.size > layout.viewportEndOffset ->
                                stripState.animateScrollBy(
                                    (item.offset + item.size - layout.viewportEndOffset).toFloat(),
                                )
                        }
                    }
                    LazyRow(
                        state = stripState,
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
                    panelBody(
                        selected,
                        state,
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
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
                    panelBody(
                        selected,
                        state,
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
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
        PerfPanel.BATTERY -> BatteryPanel(state, vm.batteryPowerHistory, vm.batteryTempHistory)
        PerfPanel.THERMAL -> ThermalPanel(state, vm.thermalHistory)
        PerfPanel.GPU -> GpuPanel(state, vm.gpuHistory)
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
                // Same auto-scale floor as the big network chart (1 KiB/s):
                // without it idle keepalive chatter renders full-height spikes
                // here while the main chart correctly shows a near-zero line.
                minScale = 1024f,
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
        PerfPanel.THERMAL -> {
            val thermal = state.thermal
            val hottest = thermal?.hottest
            MiniChartTile(
                title = stringResource(R.string.perf_tile_thermal),
                // Falls back to the OS throttling verdict where sensors are
                // blocked, so the tile is never a bare dash on those devices.
                value = hottest?.let { Format.celsius(it.temperatureC) }
                    ?: stringResource(statusLabel(thermal?.status)),
                history = vm.thermalHistory,
                windowPoints = state.windowPoints,
                selected = selected,
                onClick = onClick,
                modifier = modifier,
                secondaryValue = hottest?.name,
                // Matches the panel's fixed 0-100 °C scale; autoscale would
                // make a cool 30 °C sparkline look identical to a hot 85 °C one.
                minScale = 100f,
            )
        }
        PerfPanel.GPU -> {
            MiniChartTile(
                title = stringResource(R.string.perf_tile_gpu),
                // Live load where the vendor exposes it; "Info" (identity only)
                // elsewhere — never a fabricated 0%.
                value = state.gpuLoad?.let { Format.percent(it) }
                    ?: stringResource(R.string.perf_gpu_info_value),
                history = vm.gpuHistory,
                windowPoints = state.windowPoints,
                selected = selected,
                onClick = onClick,
                modifier = modifier,
            )
        }
    }
}
