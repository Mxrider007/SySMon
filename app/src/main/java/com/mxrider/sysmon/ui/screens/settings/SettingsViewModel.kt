package com.mxrider.sysmon.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.core.AccentOption
import com.mxrider.sysmon.core.SysMonSettings
import com.mxrider.sysmon.core.TabDestination
import com.mxrider.sysmon.core.ThemeMode
import com.mxrider.sysmon.core.UpdateSpeed
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    private val store = Graph.settings

    val settings: StateFlow<SysMonSettings> = store.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SysMonSettings.DEFAULT,
        )

    fun setThemeMode(value: ThemeMode) {
        viewModelScope.launch { store.setThemeMode(value) }
    }

    fun setAccent(value: AccentOption) {
        viewModelScope.launch { store.setAccent(value) }
    }

    fun setUpdateSpeed(value: UpdateSpeed) {
        viewModelScope.launch { store.setUpdateSpeed(value) }
    }

    fun setStartPage(value: TabDestination) {
        viewModelScope.launch { store.setStartPage(value) }
    }

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { store.setKeepScreenOn(value) }
    }

    fun setShowSystemApps(value: Boolean) {
        viewModelScope.launch { store.setShowSystemApps(value) }
    }

    fun setCpuNotification(value: Boolean) {
        viewModelScope.launch { store.setCpuNotification(value) }
    }

    fun setCpuNotificationBox(value: Boolean) {
        viewModelScope.launch { store.setCpuNotificationBox(value) }
    }

    fun setNetNotification(value: Boolean) {
        viewModelScope.launch { store.setNetNotification(value) }
    }
}
