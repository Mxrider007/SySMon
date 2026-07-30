package com.mxrider.sysmon.ui.screens.apphistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.core.UsageRange
import com.mxrider.sysmon.core.UsageRow
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the App history screen. Rows come from [Graph.usage] for
 * the selected [UsageRange]; changing the range reloads the table (with a
 * loading state). The repository degrades to an empty list without usage
 * access, so the screen shows a grant gate whenever the permission is missing.
 * Sorting happens here; the screen renders [UiState.visibleRows] as-is.
 */
class AppHistoryViewModel : ViewModel() {

    /**
     * Column sums over every row in the range. Byte totals are null when NO
     * row carried a value (no usage access): summing nothing but dashes into
     * a confident "0 B" would fabricate a measurement.
     */
    data class Totals(
        val apps: Int,
        val screenTimeMs: Long,
        val wifiBytes: Long?,
        val mobileBytes: Long?,
    )

    data class UiState(
        val rows: List<UsageRow> = emptyList(),
        /** [rows] under the active sort. */
        val visibleRows: List<UsageRow> = emptyList(),
        val totals: Totals? = null,
        val sortKey: String = COL_SCREEN_TIME,
        val ascending: Boolean = false,
        val usageAccessGranted: Boolean = true,
        val loading: Boolean = true,
        /** Epoch millis where the selected range begins ("since <date>"). */
        val rangeStartMillis: Long = rangeStart(UsageRange.TODAY),
    )

    private val repo = Graph.usage
    private val usageAccess = Graph.usageAccess

    private val _range = MutableStateFlow(UsageRange.TODAY)
    val range: StateFlow<UsageRange> = _range.asStateFlow()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _range.collect { range -> requestLoad(range) }
        }
    }

    fun setRange(range: UsageRange) {
        _range.value = range
    }

    /**
     * Reloads the current range when the data is older than [maxAgeMs]; the
     * screen calls this on every visit so an app left in recents overnight
     * doesn't present stale rows as "Today". An in-flight load counts as
     * fresh — kicking a second one would only race it.
     */
    fun refreshIfStale(maxAgeMs: Long = 60_000L) {
        if (loadJob?.isActive == true) return
        if (System.currentTimeMillis() - lastLoadedAt > maxAgeMs) {
            requestLoad(_range.value)
        }
    }

    private var lastLoadedAt = 0L
    private var loadJob: Job? = null

    /**
     * Single-flight loader: every reload path (range change, stale refresh,
     * permission recheck) funnels through here, and a newer request cancels
     * the in-flight one. Uncoordinated concurrent loads could finish out of
     * order and leave the wrong range's rows under the dropdown.
     */
    private fun requestLoad(range: UsageRange) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadNow(range) }
    }

    fun setSort(key: String, ascending: Boolean) {
        _state.update { recompute(it.copy(sortKey = key, ascending = ascending)) }
    }

    /** Polled by the screen while the usage-access gate is visible. */
    fun recheckUsageAccess() {
        viewModelScope.launch {
            val granted = withContext(Dispatchers.IO) {
                try {
                    usageAccess.hasPermission()
                } catch (e: Exception) {
                    false
                }
            }
            if (granted != _state.value.usageAccessGranted) {
                _state.update { it.copy(usageAccessGranted = granted) }
                if (granted) requestLoad(_range.value)
            }
        }
    }

    private suspend fun loadNow(range: UsageRange) {
        _state.update { it.copy(loading = true, rangeStartMillis = rangeStart(range)) }
        val granted = withContext(Dispatchers.IO) {
            try {
                usageAccess.hasPermission()
            } catch (e: Exception) {
                false
            }
        }
        val rows = withContext(Dispatchers.IO) {
            try {
                repo.usageRows(range, includeNetwork = granted)
            } catch (e: Exception) {
                emptyList<UsageRow>()
            }
        }
        _state.update {
            recompute(it.copy(rows = rows, usageAccessGranted = granted, loading = false))
        }
        lastLoadedAt = System.currentTimeMillis()
    }

    /**
     * Applies the active sort: the column comparator in the requested
     * direction, then a stable A→Z app tiebreak regardless of direction.
     */
    private fun recompute(state: UiState): UiState {
        val totals = if (state.rows.isEmpty()) {
            null
        } else {
            Totals(
                apps = state.rows.size,
                screenTimeMs = state.rows.sumOf { it.screenTimeMs },
                wifiBytes = state.rows.mapNotNull { it.wifiBytes }
                    .takeIf { it.isNotEmpty() }?.sum(),
                mobileBytes = state.rows.mapNotNull { it.mobileBytes }
                    .takeIf { it.isNotEmpty() }?.sum(),
            )
        }
        val primary = comparatorFor(state.sortKey)
            ?: return state.copy(visibleRows = state.rows, totals = totals)
        val directed = if (state.ascending) primary else primary.reversed()
        return state.copy(
            visibleRows = state.rows.sortedWith(directed.then(COMPARATOR_APP)),
            totals = totals,
        )
    }

    companion object {
        const val COL_APP = "app"
        const val COL_SCREEN_TIME = "screenTime"
        const val COL_LAUNCHES = "launches"
        const val COL_WIFI = "wifi"
        const val COL_MOBILE = "mobile"
        const val COL_LAST_USED = "lastUsed"

        private const val DAY_MS = 86_400_000L

        val COMPARATOR_APP: Comparator<UsageRow> =
            compareBy<UsageRow, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
                .thenBy { it.packageName }

        val COMPARATOR_SCREEN_TIME: Comparator<UsageRow> = compareBy { it.screenTimeMs }

        /** Nulls (multi-day ranges have no launch counts) sort below every real value. */
        val COMPARATOR_LAUNCHES: Comparator<UsageRow> = compareBy { it.launches ?: -1 }

        /** Nulls (no usage access) sort below every real value. */
        val COMPARATOR_WIFI: Comparator<UsageRow> = compareBy { it.wifiBytes ?: -1L }

        val COMPARATOR_MOBILE: Comparator<UsageRow> = compareBy { it.mobileBytes ?: -1L }

        val COMPARATOR_LAST_USED: Comparator<UsageRow> = compareBy { it.lastUsed }

        fun comparatorFor(key: String): Comparator<UsageRow>? = when (key) {
            COL_APP -> COMPARATOR_APP
            COL_SCREEN_TIME -> COMPARATOR_SCREEN_TIME
            COL_LAUNCHES -> COMPARATOR_LAUNCHES
            COL_WIFI -> COMPARATOR_WIFI
            COL_MOBILE -> COMPARATOR_MOBILE
            COL_LAST_USED -> COMPARATOR_LAST_USED
            else -> null
        }

        /** Where the range begins; mirrors the repository's window logic. */
        fun rangeStart(range: UsageRange, now: Long = System.currentTimeMillis()): Long =
            when (range) {
                UsageRange.TODAY -> {
                    val cal = Calendar.getInstance()
                    cal.timeInMillis = now
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    cal.timeInMillis
                }
                UsageRange.DAYS_7 -> now - 7L * DAY_MS
                UsageRange.DAYS_30 -> now - 30L * DAY_MS
            }
    }
}
