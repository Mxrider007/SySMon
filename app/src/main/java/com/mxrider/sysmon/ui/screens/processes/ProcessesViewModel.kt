package com.mxrider.sysmon.ui.screens.processes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.core.ProcessRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the Processes screen. Rows come from [Graph.processes]
 * (usage-stats based; the repository degrades to INACTIVE installed apps when
 * usage access is missing) and are refreshed on init, every 30 seconds and on
 * demand via [refresh]. Filtering (query + system apps) and sorting happen
 * here; the screen renders [UiState.visibleRows] as-is.
 */
class ProcessesViewModel : ViewModel() {

    enum class InfoKind { KILL_REQUESTED, KILL_UNSUPPORTED }

    /** Transient status message shown near the bottom bar; auto-clears after 4s. */
    data class InfoMessage(val kind: InfoKind, val appLabel: String)

    data class UiState(
        val rows: List<ProcessRow> = emptyList(),
        /** [rows] after the query/system filters and the active sort. */
        val visibleRows: List<ProcessRow> = emptyList(),
        val query: String = "",
        val sortKey: String = COL_SCREEN_TIME,
        val ascending: Boolean = false,
        val selectedPkg: String? = null,
        val showSystem: Boolean = false,
        val usageAccessGranted: Boolean = true,
        val loading: Boolean = true,
        val infoMessage: InfoMessage? = null,
    )

    private val repo = Graph.processes
    private val taskKiller = Graph.taskKiller
    private val usageAccess = Graph.usageAccess
    private val settingsStore = Graph.settings

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var infoJob: Job? = null

    init {
        viewModelScope.launch {
            val showSystem = try {
                settingsStore.settings.first().showSystemApps
            } catch (e: Exception) {
                false
            }
            _state.update { recompute(it.copy(showSystem = showSystem)) }
            while (true) {
                refreshNow()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    fun setQuery(query: String) {
        _state.update { recompute(it.copy(query = query)) }
    }

    fun setSort(key: String, ascending: Boolean) {
        _state.update { recompute(it.copy(sortKey = key, ascending = ascending)) }
    }

    /** Local override; deliberately not written back to the settings store. */
    fun setShowSystem(show: Boolean) {
        _state.update { recompute(it.copy(showSystem = show)) }
    }

    fun select(packageName: String?) {
        _state.update { it.copy(selectedPkg = packageName) }
    }

    /**
     * Best-effort background kill. [TaskKiller.kill][com.mxrider.sysmon.core.TaskKiller.kill]
     * returns false on API levels where the system ignores the request.
     */
    fun endTask(row: ProcessRow) {
        viewModelScope.launch {
            val killed = withContext(Dispatchers.IO) {
                try {
                    taskKiller.kill(row.packageName)
                } catch (e: Exception) {
                    false
                }
            }
            showInfo(
                InfoMessage(
                    kind = if (killed) InfoKind.KILL_REQUESTED else InfoKind.KILL_UNSUPPORTED,
                    appLabel = row.label,
                ),
            )
        }
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
                if (granted) refreshNow()
            }
        }
    }

    private suspend fun refreshNow() {
        val granted = withContext(Dispatchers.IO) {
            try {
                usageAccess.hasPermission()
            } catch (e: Exception) {
                false
            }
        }
        val rows = withContext(Dispatchers.IO) {
            try {
                repo.processRows()
            } catch (e: Exception) {
                emptyList<ProcessRow>()
            }
        }
        _state.update {
            recompute(it.copy(rows = rows, usageAccessGranted = granted, loading = false))
        }
    }

    private fun showInfo(message: InfoMessage) {
        infoJob?.cancel()
        _state.update { it.copy(infoMessage = message) }
        infoJob = viewModelScope.launch {
            delay(INFO_MESSAGE_MS)
            _state.update { it.copy(infoMessage = null) }
        }
    }

    private fun recompute(state: UiState): UiState {
        val query = state.query.trim()
        var list = if (state.showSystem) state.rows else state.rows.filter { !it.isSystem }
        if (query.isNotEmpty()) {
            list = list.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
        val comparator = comparatorFor(state.sortKey)
        if (comparator != null) {
            list = list.sortedWith(if (state.ascending) comparator else comparator.reversed())
        }
        // Drop the selection when its row is filtered/refreshed away so
        // "End task" never targets an invisible row.
        val selected = state.selectedPkg?.takeIf { pkg -> list.any { it.packageName == pkg } }
        return state.copy(visibleRows = list, selectedPkg = selected)
    }

    companion object {
        const val COL_APP = "app"
        const val COL_STATUS = "status"
        const val COL_SCREEN_TIME = "screenTime"
        const val COL_NETWORK = "network"
        const val COL_STORAGE = "storage"

        private const val REFRESH_INTERVAL_MS = 30_000L
        private const val INFO_MESSAGE_MS = 4_000L

        val COMPARATOR_APP: Comparator<ProcessRow> =
            compareBy<ProcessRow, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
                .thenBy { it.packageName }

        val COMPARATOR_STATUS: Comparator<ProcessRow> =
            compareBy<ProcessRow> { it.status }.then(COMPARATOR_APP)

        val COMPARATOR_SCREEN_TIME: Comparator<ProcessRow> =
            compareBy<ProcessRow> { it.foregroundTimeTodayMs }.then(COMPARATOR_APP)

        /** Nulls (no usage access) sort below every real value. */
        val COMPARATOR_NETWORK: Comparator<ProcessRow> =
            compareBy<ProcessRow> { it.networkTodayBytes ?: -1L }.then(COMPARATOR_APP)

        val COMPARATOR_STORAGE: Comparator<ProcessRow> =
            compareBy<ProcessRow> { it.storageBytes ?: -1L }.then(COMPARATOR_APP)

        fun comparatorFor(key: String): Comparator<ProcessRow>? = when (key) {
            COL_APP -> COMPARATOR_APP
            COL_STATUS -> COMPARATOR_STATUS
            COL_SCREEN_TIME -> COMPARATOR_SCREEN_TIME
            COL_NETWORK -> COMPARATOR_NETWORK
            COL_STORAGE -> COMPARATOR_STORAGE
            else -> null
        }
    }
}
