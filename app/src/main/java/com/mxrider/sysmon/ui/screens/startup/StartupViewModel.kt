package com.mxrider.sysmon.ui.screens.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mxrider.sysmon.data.apps.PackageEvents
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.core.StartupRow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the Startup apps screen. Rows come from [Graph.startup]
 * (apps declaring BOOT_COMPLETED receivers); loaded once on init and on
 * demand via [refresh]. Default sort: startup impact (high first), then app
 * label A→Z. Sorting happens here; the screen renders [UiState.visibleRows]
 * as-is.
 */
class StartupViewModel : ViewModel() {

    data class UiState(
        val rows: List<StartupRow> = emptyList(),
        /** [rows] under the active sort. */
        val visibleRows: List<StartupRow> = emptyList(),
        val sortKey: String = COL_IMPACT,
        val ascending: Boolean = false,
        val selectedPkg: String? = null,
        val loading: Boolean = true,
    )

    private val repo = Graph.startup

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        // Package churn reloads immediately; the 60-second staleness
        // window stays as the fallback.
        viewModelScope.launch {
            PackageEvents.generation.drop(1).collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val rows = withContext(Dispatchers.IO) {
                try {
                    repo.startupRows()
                } catch (e: Exception) {
                    emptyList<StartupRow>()
                }
            }
            _state.update { recompute(it.copy(rows = rows, loading = false)) }
        }
    }

    fun setSort(key: String, ascending: Boolean) {
        _state.update { recompute(it.copy(sortKey = key, ascending = ascending)) }
    }

    fun select(packageName: String?) {
        _state.update { it.copy(selectedPkg = packageName) }
    }

    /**
     * Applies the active sort: the column comparator in the requested
     * direction, then a stable A→Z app tiebreak regardless of direction.
     * Drops the selection when its row disappears after a refresh so
     * "App info" never targets an invisible row.
     */
    private fun recompute(state: UiState): UiState {
        val primary = comparatorFor(state.sortKey)
        val list = if (primary == null) {
            state.rows
        } else {
            val directed = if (state.ascending) primary else primary.reversed()
            state.rows.sortedWith(directed.then(COMPARATOR_APP))
        }
        val selected = state.selectedPkg?.takeIf { pkg -> list.any { it.packageName == pkg } }
        return state.copy(visibleRows = list, selectedPkg = selected)
    }

    companion object {
        const val COL_APP = "app"
        const val COL_STATUS = "status"
        const val COL_RECEIVERS = "receivers"
        const val COL_IMPACT = "impact"

        val COMPARATOR_APP: Comparator<StartupRow> =
            compareBy<StartupRow, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
                .thenBy { it.packageName }

        val COMPARATOR_STATUS: Comparator<StartupRow> = compareBy { it.enabled }

        val COMPARATOR_RECEIVERS: Comparator<StartupRow> = compareBy { it.receiverNames.size }

        val COMPARATOR_IMPACT: Comparator<StartupRow> = compareBy { it.impact }

        fun comparatorFor(key: String): Comparator<StartupRow>? = when (key) {
            COL_APP -> COMPARATOR_APP
            COL_STATUS -> COMPARATOR_STATUS
            COL_RECEIVERS -> COMPARATOR_RECEIVERS
            COL_IMPACT -> COMPARATOR_IMPACT
            else -> null
        }
    }
}
