package com.mxrider.sysmon.ui.screens.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.core.UserRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the Users screen. [Graph.users] always returns at least
 * the current user and never throws, but the load is still wrapped and run
 * on [Dispatchers.IO] per the contracts.
 */
class UsersViewModel : ViewModel() {

    data class UiState(
        val users: List<UserRow> = emptyList(),
        val loading: Boolean = true,
    )

    private val repo = Graph.users

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val users = withContext(Dispatchers.IO) {
                try {
                    repo.users()
                } catch (e: Exception) {
                    emptyList<UserRow>()
                }
            }
            _state.update { it.copy(users = users, loading = false) }
        }
    }
}
