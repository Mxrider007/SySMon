package com.mxrider.sysmon.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.core.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the Details screen: the dense per-app inventory table.
 * Rows come from [Graph.apps] once on init (and on demand via [refresh]);
 * query filtering (label + package) and sorting happen here so the screen
 * renders [UiState.visibleRows] as-is.
 */
class DetailsViewModel : ViewModel() {

    data class UiState(
        val rows: List<AppEntry> = emptyList(),
        /** [rows] after the query filter and the active sort. */
        val visibleRows: List<AppEntry> = emptyList(),
        val query: String = "",
        val sortKey: String = COL_APP,
        val ascending: Boolean = true,
        val selectedPkg: String? = null,
        val loading: Boolean = true,
    )

    private val repo = Graph.apps

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                try {
                    repo.installedApps()
                } catch (e: Exception) {
                    emptyList<AppEntry>()
                }
            }
            _state.update { recompute(it.copy(rows = rows, loading = false)) }
            lastLoadedAt = System.currentTimeMillis()
        }
    }

    private var lastLoadedAt = 0L

    /**
     * The VM is activity-scoped and the activity handles config changes
     * itself, so without this on-visit recheck an app installed or removed
     * after the first load would never appear or disappear from the table.
     */
    fun refreshIfStale(maxAgeMs: Long = 60_000L) {
        if (lastLoadedAt != 0L && System.currentTimeMillis() - lastLoadedAt > maxAgeMs) {
            refresh()
        }
    }

    fun setQuery(query: String) {
        _state.update { recompute(it.copy(query = query)) }
    }

    fun setSort(key: String, ascending: Boolean) {
        _state.update { recompute(it.copy(sortKey = key, ascending = ascending)) }
    }

    fun select(packageName: String?) {
        _state.update { it.copy(selectedPkg = packageName) }
    }

    private fun recompute(state: UiState): UiState {
        val query = state.query.trim()
        var list = state.rows
        if (query.isNotEmpty()) {
            list = list.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
        val comparator = comparatorFor(state.sortKey)
        if (comparator != null) {
            // Tiebreak AFTER the direction flip — a reversed embedded tiebreak
            // would list equal-value groups Z→A.
            val directed = if (state.ascending) comparator else comparator.reversed()
            list = list.sortedWith(directed.then(COMPARATOR_APP))
        }
        // Drop the selection when its row is filtered/refreshed away so the
        // detail strip never shows an invisible row.
        val selected = state.selectedPkg?.takeIf { pkg -> list.any { it.packageName == pkg } }
        return state.copy(visibleRows = list, selectedPkg = selected)
    }

    companion object {
        const val COL_APP = "app"
        const val COL_PACKAGE = "package"
        const val COL_VERSION = "version"
        const val COL_TARGET_SDK = "targetSdk"
        const val COL_MIN_SDK = "minSdk"
        const val COL_SIZE = "size"
        const val COL_INSTALLED = "installed"
        const val COL_UPDATED = "updated"
        const val COL_INSTALLER = "installer"
        const val COL_ENABLED = "enabled"

        val COMPARATOR_APP: Comparator<AppEntry> =
            compareBy<AppEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
                .thenBy { it.packageName }

        val COMPARATOR_PACKAGE: Comparator<AppEntry> =
            compareBy { it.packageName }

        // Pure primaries — recompute() appends the A→Z tiebreak after the
        // direction flip, so it must not be baked in here.
        /** versionCode, not versionName: lexicographic puts "10.0" before "9.0". */
        val COMPARATOR_VERSION: Comparator<AppEntry> =
            compareBy { it.versionCode }

        val COMPARATOR_TARGET_SDK: Comparator<AppEntry> =
            compareBy { it.targetSdk }

        val COMPARATOR_MIN_SDK: Comparator<AppEntry> =
            compareBy { it.minSdk }

        val COMPARATOR_SIZE: Comparator<AppEntry> =
            compareBy { it.apkSizeBytes }

        val COMPARATOR_INSTALLED: Comparator<AppEntry> =
            compareBy { it.installTime }

        val COMPARATOR_UPDATED: Comparator<AppEntry> =
            compareBy { it.updateTime }

        /** Nulls (sideloaded / unknown installer) sort below every real value. */
        val COMPARATOR_INSTALLER: Comparator<AppEntry> =
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.installerPackage ?: "" }

        val COMPARATOR_ENABLED: Comparator<AppEntry> =
            compareByDescending { it.enabled }

        fun comparatorFor(key: String): Comparator<AppEntry>? = when (key) {
            COL_APP -> COMPARATOR_APP
            COL_PACKAGE -> COMPARATOR_PACKAGE
            COL_VERSION -> COMPARATOR_VERSION
            COL_TARGET_SDK -> COMPARATOR_TARGET_SDK
            COL_MIN_SDK -> COMPARATOR_MIN_SDK
            COL_SIZE -> COMPARATOR_SIZE
            COL_INSTALLED -> COMPARATOR_INSTALLED
            COL_UPDATED -> COMPARATOR_UPDATED
            COL_INSTALLER -> COMPARATOR_INSTALLER
            COL_ENABLED -> COMPARATOR_ENABLED
            else -> null
        }
    }
}
