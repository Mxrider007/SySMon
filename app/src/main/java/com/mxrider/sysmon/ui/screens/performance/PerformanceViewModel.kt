package com.mxrider.sysmon.ui.screens.performance

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.core.BatterySnapshot
import com.mxrider.sysmon.core.ChartHistory
import com.mxrider.sysmon.core.CpuSnapshot
import com.mxrider.sysmon.core.CpuSource
import com.mxrider.sysmon.core.CpuStaticInfo
import com.mxrider.sysmon.core.GpuInfo
import com.mxrider.sysmon.core.MemorySnapshot
import com.mxrider.sysmon.core.NetworkSnapshot
import com.mxrider.sysmon.core.StorageSnapshot
import com.mxrider.sysmon.core.ThermalSnapshot
import com.mxrider.sysmon.core.UpdateSpeed
import com.mxrider.sysmon.core.ticker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Latest snapshots plus chart-window parameters for the Performance screen.
 * Nulls mean "not sampled yet" — panels render dashes / loading, never fake
 * values.
 */
data class PerfUiState(
    val cpu: CpuSnapshot? = null,
    val cpuStatic: CpuStaticInfo? = null,
    val memory: MemorySnapshot? = null,
    val network: NetworkSnapshot? = null,
    val battery: BatterySnapshot? = null,
    val storage: StorageSnapshot? = null,
    val thermal: ThermalSnapshot? = null,
    val gpu: GpuInfo? = null,
    /** Total GPU busy fraction; null wherever the vendor blocks the node. */
    val gpuLoad: Float? = null,
    /** Samples visible in each chart = 60 s / update interval, min 15. */
    val windowPoints: Int = (60_000L / UpdateSpeed.NORMAL.intervalMs).toInt(),
    val intervalMs: Long = UpdateSpeed.NORMAL.intervalMs,
    /** Bumps once per-core histories exist so consumers re-read them. */
    val coreHistoryCount: Int = 0,
)

class PerformanceViewModel : ViewModel() {

    val cpuHistory = ChartHistory(HISTORY_CAPACITY)
    val memoryHistory = ChartHistory(HISTORY_CAPACITY)
    val netRxHistory = ChartHistory(HISTORY_CAPACITY)
    val netTxHistory = ChartHistory(HISTORY_CAPACITY)
    val batteryHistory = ChartHistory(HISTORY_CAPACITY)
    val batteryPowerHistory = ChartHistory(HISTORY_CAPACITY)
    val batteryTempHistory = ChartHistory(HISTORY_CAPACITY)

    /** Fed with the latest used fraction each tick (flat line between storage samples). */
    val storageHistory = ChartHistory(HISTORY_CAPACITY)

    /** Hottest zone per tick; NaN whenever no sensor is readable. */
    val thermalHistory = ChartHistory(HISTORY_CAPACITY)

    /** Total GPU load where the vendor node is readable; NaN (blank) elsewhere. */
    val gpuHistory = ChartHistory(HISTORY_CAPACITY)

    /** Created after the first CPU sample reveals the core count; max 16 tracked. */
    var coreHistories: List<ChartHistory> = emptyList()
        private set

    private val _uiState = MutableStateFlow(PerfUiState())
    val uiState: StateFlow<PerfUiState> = _uiState.asStateFlow()

    private var lastStorage: StorageSnapshot? = null
    private var gpuRequested = false

    /** elapsedRealtime of the last sample pushed; 0 = none since the last clear. */
    private var lastTickAt = 0L

    init {
        // Static CPU info once, off the main thread.
        viewModelScope.launch {
            val info = try {
                withContext(Dispatchers.IO) { Graph.cpu.staticInfo }
            } catch (_: Exception) {
                null
            }
            if (info != null) _uiState.update { it.copy(cpuStatic = info) }
        }

        // Keep windowPoints in sync with the selected speed. PAUSED retains
        // the previous window so frozen charts keep their scale. When the
        // cadence actually changes, drop the buffers: 500ms and 4s samples
        // mixed under one "60 seconds" axis would be a wrong timeline.
        viewModelScope.launch {
            var appliedIntervalMs: Long? = null
            Graph.settings.settings
                .map { it.updateSpeed }
                .distinctUntilChanged()
                .collect { speed ->
                    if (speed != UpdateSpeed.PAUSED) {
                        val interval = speed.intervalMs
                        if (appliedIntervalMs != null && appliedIntervalMs != interval) {
                            clearHistories()
                        }
                        appliedIntervalMs = interval
                        _uiState.update {
                            it.copy(
                                intervalMs = interval,
                                windowPoints = windowPointsFor(interval),
                            )
                        }
                    }
                }
        }

        // Sampling loop, gated on active UI subscribers. The VM is activity-
        // scoped and outlives the screen (Crossfade tabs), so an ungated
        // ticker would keep hitting /proc, sysfs and the battery HAL behind
        // other tabs and after Home - a system monitor must not itself drain
        // the battery. collectAsStateWithLifecycle drops the subscription on
        // STOP/tab-switch, which cancels the inner collect via collectLatest.
        viewModelScope.launch {
            _uiState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (active) {
                        // With PAUSED persisted the ticker never fires; take
                        // one sample per visit so panels freeze on real data
                        // instead of showing "Loading" forever. The delta
                        // sources are primed first so that sample covers one
                        // real second, not the whole time since the last visit.
                        val speed = try {
                            Graph.settings.settings.first().updateSpeed
                        } catch (_: Exception) {
                            UpdateSpeed.NORMAL
                        }
                        if (speed == UpdateSpeed.PAUSED) {
                            withContext(Dispatchers.IO) {
                                runCatching { Graph.network.sample() }
                                runCatching { Graph.cpu.sample() }
                            }
                            delay(1_000)
                            onTick(0L, live = false)
                        }
                        ticker(Graph.settings.settings.map { it.updateSpeed }).collect { tick ->
                            onTick(tick)
                        }
                    }
                }
        }
    }

    private fun clearHistories() {
        cpuHistory.clear()
        memoryHistory.clear()
        netRxHistory.clear()
        netTxHistory.clear()
        batteryHistory.clear()
        batteryPowerHistory.clear()
        batteryTempHistory.clear()
        storageHistory.clear()
        thermalHistory.clear()
        gpuHistory.clear()
        coreHistories.forEach { it.clear() }
        lastTickAt = 0L
    }

    private fun pushGap(points: Int) {
        val all = listOf(
            cpuHistory, memoryHistory, netRxHistory, netTxHistory, batteryHistory,
            batteryPowerHistory, batteryTempHistory, storageHistory, thermalHistory, gpuHistory,
        ) + coreHistories
        for (history in all) repeat(points) { history.push(Float.NaN) }
    }

    /** Loads GPU info the first time the GPU panel is shown; provider caches. */
    fun loadGpuInfo() {
        if (gpuRequested) return
        gpuRequested = true
        viewModelScope.launch {
            val info = try {
                withContext(Dispatchers.IO) { Graph.gpu.get() }
            } catch (_: Exception) {
                GpuInfo(null, null, null)
            }
            _uiState.update { it.copy(gpu = info) }
        }
    }

    /** [live] = a ticker tick; false for the one-shot sample of a PAUSED visit. */
    private suspend fun onTick(tick: Long, live: Boolean = true) {
        val sampleStorage = tick % STORAGE_SAMPLE_PERIOD_TICKS == 0L
        val batch = try {
            withContext(Dispatchers.IO) {
                TickBatch(
                    cpu = Graph.cpu.sample(),
                    memory = Graph.memory.sample(),
                    network = Graph.network.sample(),
                    battery = Graph.battery.sample(),
                    storage = if (sampleStorage) Graph.storage.sample() else null,
                    thermal = Graph.thermal.sample(),
                    gpuLoad = Graph.gpuLoad.sample(),
                )
            }
        } catch (_: Exception) {
            // Monitors degrade internally; if one still throws, skip the tick.
            return
        }

        // ChartHistory is not thread-safe: push on the main thread only.

        // Sampling stops while the screen is away (another tab, Home). Blank
        // the time it was gone instead of joining old samples straight onto
        // new ones under the "60 seconds" axis. On the first tick back the
        // delta sources (network counters, /proc/stat CPU) report the average
        // over the whole absence, so their point is blanked as well.
        val now = SystemClock.elapsedRealtime()
        val intervalMs = _uiState.value.intervalMs
        val missed = if (live && lastTickAt > 0L && intervalMs > 0L) {
            (now - lastTickAt) / intervalMs - 1
        } else {
            0L
        }
        lastTickAt = now
        val afterGap = missed >= 2
        if (afterGap) pushGap(missed.coerceAtMost(HISTORY_CAPACITY.toLong()).toInt())
        val cpuBlank = batch.cpu.source == CpuSource.UNAVAILABLE ||
            (afterGap && batch.cpu.source == CpuSource.PROC_STAT)

        cpuHistory.push(
            if (cpuBlank) Float.NaN
            else batch.cpu.utilization.coerceIn(0f, 1f),
        )
        if (coreHistories.isEmpty() && batch.cpu.perCore.isNotEmpty()) {
            val tracked = batch.cpu.perCore.size.coerceAtMost(MAX_TRACKED_CORES)
            coreHistories = List(tracked) { ChartHistory(HISTORY_CAPACITY) }
        }
        coreHistories.forEachIndexed { index, history ->
            val core = batch.cpu.perCore.getOrNull(index)?.takeUnless { cpuBlank }
            history.push(core?.utilization?.coerceIn(0f, 1f) ?: Float.NaN)
        }

        val mem = batch.memory
        memoryHistory.push(
            if (mem.totalBytes > 0) (mem.usedBytes.toFloat() / mem.totalBytes).coerceIn(0f, 1f)
            else Float.NaN,
        )

        netRxHistory.push(
            if (afterGap) Float.NaN else batch.network.rxBytesPerSec.toFloat().coerceAtLeast(0f),
        )
        netTxHistory.push(
            if (afterGap) Float.NaN else batch.network.txBytesPerSec.toFloat().coerceAtLeast(0f),
        )
        // Fraction like CPU/memory: the MiniChartTile autoscale would otherwise
        // render 42% battery as a nearly-full sparkline (nice-ceiling of 50).
        batteryHistory.push((batch.battery.levelPercent / 100f).coerceIn(0f, 1f))
        // Magnitude: PerfChart plots 0..max, and the panel's signed Power stat
        // right under the chart carries the charging/discharging direction.
        batteryPowerHistory.push(
            batch.battery.powerWatts?.let { kotlin.math.abs(it).toFloat() } ?: Float.NaN,
        )
        batteryTempHistory.push(batch.battery.temperatureC?.toFloat() ?: Float.NaN)
        // NaN renders the sparkline blank ("no data") wherever the vendor
        // blocks its load node, where a pushed 0 would fabricate a measured
        // flat 0 % — the exact thing the app promises never to do.
        gpuHistory.push(batch.gpuLoad ?: Float.NaN)

        if (batch.storage != null) lastStorage = batch.storage
        val storage = lastStorage
        storageHistory.push(storageUsedFraction(storage) ?: Float.NaN)
        // No readable sensor pushes NaN (blank chart = "no data"), never a 0 °C
        // that would read as a real, absurdly cold measurement.
        thermalHistory.push(
            batch.thermal.hottest?.temperatureC?.toFloat() ?: Float.NaN,
        )

        _uiState.update {
            it.copy(
                cpu = batch.cpu,
                memory = batch.memory,
                network = batch.network,
                battery = batch.battery,
                storage = storage,
                thermal = batch.thermal,
                gpuLoad = batch.gpuLoad,
                coreHistoryCount = coreHistories.size,
            )
        }
    }

    private fun windowPointsFor(intervalMs: Long): Int =
        if (intervalMs <= 0L) MIN_WINDOW_POINTS
        else (60_000L / intervalMs).toInt().coerceAtLeast(MIN_WINDOW_POINTS)

    private companion object {
        const val HISTORY_CAPACITY = 240
        const val MAX_TRACKED_CORES = 16
        const val STORAGE_SAMPLE_PERIOD_TICKS = 10L
        const val MIN_WINDOW_POINTS = 15
    }
}

private data class TickBatch(
    val cpu: CpuSnapshot,
    val memory: MemorySnapshot,
    val network: NetworkSnapshot,
    val battery: BatterySnapshot,
    val storage: StorageSnapshot?,
    val thermal: ThermalSnapshot,
    val gpuLoad: Float?,
)
