package com.mxrider.sysmon.ui.screens.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.core.ServiceRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the Services screen: the static manifest inventory of
 * declared services from [Graph.services]. The list routinely exceeds 2000
 * rows on app-heavy devices, so filtering + sorting always run off the main
 * thread ([Dispatchers.Default]) and only the finished immutable list is
 * published to [UiState.visibleRows]. Input fields (query, toggle, sort)
 * update synchronously so the controls stay responsive.
 */
class ServicesViewModel : ViewModel() {

    data class UiState(
        val rows: List<ServiceRow> = emptyList(),
        /** [rows] after the query/system filters and the active sort. */
        val visibleRows: List<ServiceRow> = emptyList(),
        val query: String = "",
        /** Win11-style group toggle; system apps hidden by default. */
        val hideSystem: Boolean = true,
        val sortKey: String = COL_SERVICE,
        val ascending: Boolean = true,
        val loading: Boolean = true,
    )

    private val repo = Graph.services

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var recomputeJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                try {
                    repo.serviceRows()
                } catch (e: Exception) {
                    emptyList<ServiceRow>()
                }
            }
            _state.update { it.copy(rows = rows, loading = false) }
            scheduleRecompute()
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        scheduleRecompute()
    }

    fun setHideSystem(hide: Boolean) {
        _state.update { it.copy(hideSystem = hide) }
        scheduleRecompute()
    }

    fun setSort(key: String, ascending: Boolean) {
        _state.update { it.copy(sortKey = key, ascending = ascending) }
        scheduleRecompute()
    }

    /**
     * Rebuilds [UiState.visibleRows] on [Dispatchers.Default]. A newer call
     * cancels the previous one (rapid typing), and the result is applied only
     * when the inputs it was computed from are still current.
     */
    private fun scheduleRecompute() {
        recomputeJob?.cancel()
        recomputeJob = viewModelScope.launch(Dispatchers.Default) {
            val snapshot = _state.value
            val visible = computeVisible(snapshot)
            ensureActive()
            _state.update { current ->
                val inputsUnchanged = current.rows === snapshot.rows &&
                    current.query == snapshot.query &&
                    current.hideSystem == snapshot.hideSystem &&
                    current.sortKey == snapshot.sortKey &&
                    current.ascending == snapshot.ascending
                if (inputsUnchanged) current.copy(visibleRows = visible) else current
            }
        }
    }

    private fun computeVisible(state: UiState): List<ServiceRow> {
        var list = if (state.hideSystem) state.rows.filter { !it.isSystemApp } else state.rows
        val query = state.query.trim()
        if (query.isNotEmpty()) {
            list = list.filter {
                it.serviceName.contains(query, ignoreCase = true) ||
                    it.fqcn.contains(query, ignoreCase = true) ||
                    it.appLabel.contains(query, ignoreCase = true)
            }
        }
        val comparator = comparatorFor(state.sortKey) ?: return list
        return list.sortedWith(if (state.ascending) comparator else comparator.reversed())
    }

    companion object {
        const val COL_SERVICE = "service"
        const val COL_APP = "app"
        const val COL_EXPORTED = "exported"
        const val COL_ENABLED = "enabled"
        const val COL_FGS = "fgs"
        const val COL_PERMISSION = "permission"

        val COMPARATOR_SERVICE: Comparator<ServiceRow> =
            compareBy<ServiceRow, String>(String.CASE_INSENSITIVE_ORDER) { it.serviceName }
                .thenBy { it.fqcn }
                .thenBy { it.packageName }

        val COMPARATOR_APP: Comparator<ServiceRow> =
            compareBy<ServiceRow, String>(String.CASE_INSENSITIVE_ORDER) { it.appLabel }
                .thenBy { it.packageName }
                .then(COMPARATOR_SERVICE)

        /** Ascending puts exported ("Yes") rows first. */
        val COMPARATOR_EXPORTED: Comparator<ServiceRow> =
            compareByDescending<ServiceRow> { it.exported }.then(COMPARATOR_SERVICE)

        val COMPARATOR_ENABLED: Comparator<ServiceRow> =
            compareByDescending<ServiceRow> { it.enabled }.then(COMPARATOR_SERVICE)

        /** Empty (no declared FGS types) sorts below every real value. */
        val COMPARATOR_FGS: Comparator<ServiceRow> =
            compareBy<ServiceRow, String>(String.CASE_INSENSITIVE_ORDER) { it.foregroundServiceTypes }
                .then(COMPARATOR_SERVICE)

        val COMPARATOR_PERMISSION: Comparator<ServiceRow> =
            compareBy<ServiceRow, String>(String.CASE_INSENSITIVE_ORDER) { it.permission ?: "" }
                .then(COMPARATOR_SERVICE)

        fun comparatorFor(key: String): Comparator<ServiceRow>? = when (key) {
            COL_SERVICE -> COMPARATOR_SERVICE
            COL_APP -> COMPARATOR_APP
            COL_EXPORTED -> COMPARATOR_EXPORTED
            COL_ENABLED -> COMPARATOR_ENABLED
            COL_FGS -> COMPARATOR_FGS
            COL_PERMISSION -> COMPARATOR_PERMISSION
            else -> null
        }
    }
}
