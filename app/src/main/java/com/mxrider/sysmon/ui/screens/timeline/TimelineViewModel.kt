package com.mxrider.sysmon.ui.screens.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.core.LogInterval
import com.mxrider.sysmon.core.SysMonSettings
import com.mxrider.sysmon.data.logging.LogBounds
import com.mxrider.sysmon.data.logging.PerfLogReader
import com.mxrider.sysmon.data.logging.TimelineSeries
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Chartable window lengths; null span = everything the ring still holds. */
enum class TimelineRange(val millis: Long?) {
    HOUR_1(60L * 60 * 1000),
    HOUR_6(6L * 60 * 60 * 1000),
    DAY_1(24L * 60 * 60 * 1000),
    DAY_7(7L * 24 * 60 * 60 * 1000),
    ALL(null),
}

/**
 * Null [bounds] = the log is empty (or unreadable) — the screen shows the
 * empty state, never fake charts. [series] can lag one load behind a range
 * switch; the previous charts stay up instead of flashing a spinner.
 */
data class TimelineUiState(
    val range: TimelineRange = TimelineRange.DAY_1,
    val loading: Boolean = true,
    val bounds: LogBounds? = null,
    val series: TimelineSeries? = null,
)

/**
 * Loads windows of the perf-log ring for the Timeline screen. Loads are
 * gated on active UI subscribers exactly like PerformanceViewModel's sampling
 * loop — this VM outlives the screen (Crossfade tabs), and re-reading a
 * 50 MB ring behind other tabs would be self-inflicted battery drain.
 * While recording is on, the visible window refreshes every [REFRESH_MS];
 * a static log is read once per visit/range instead of polled.
 */
class TimelineViewModel : ViewModel() {

    private val reader = PerfLogReader(Graph.perfLogger)

    val settings: StateFlow<SysMonSettings> = Graph.settings.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SysMonSettings.DEFAULT,
        )

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (!active) return@collectLatest
                    combine(
                        _uiState.map { it.range }.distinctUntilChanged(),
                        Graph.settings.settings.map { it.logging }.distinctUntilChanged(),
                        // Emptiness only — reacting to every append would
                        // re-read the ring at the log cadence.
                        Graph.perfLogger.status.map { it.rowCount == 0L }.distinctUntilChanged(),
                    ) { range, logging, _ -> range to logging }
                        .collectLatest { (range, logging) ->
                            while (true) {
                                loadOnce(range)
                                if (!logging) break
                                delay(REFRESH_MS)
                            }
                        }
                }
        }
    }

    fun setRange(range: TimelineRange) {
        _uiState.update { it.copy(range = range) }
    }

    fun setLogging(value: Boolean) {
        viewModelScope.launch { Graph.settings.setLogging(value) }
    }

    fun setLogInterval(value: LogInterval) {
        viewModelScope.launch { Graph.settings.setLogInterval(value) }
    }

    private suspend fun loadOnce(range: TimelineRange) {
        val bounds = reader.bounds()
        if (bounds == null) {
            _uiState.update { it.copy(loading = false, bounds = null, series = null) }
            return
        }
        val end = bounds.lastMs
        val start = range.millis?.let { maxOf(bounds.firstMs, end - it) } ?: bounds.firstMs
        val series = reader.load(start, end)
        _uiState.update { it.copy(loading = false, bounds = bounds, series = series) }
    }

    private companion object {
        const val REFRESH_MS = 60_000L
    }
}
