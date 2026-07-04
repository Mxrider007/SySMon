package com.mxrider.sysmon.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.core.AccentOption
import com.mxrider.sysmon.core.SysMonSettings
import com.mxrider.sysmon.core.TabDestination
import com.mxrider.sysmon.core.ThemeMode
import com.mxrider.sysmon.ui.components.NavRail
import com.mxrider.sysmon.ui.onboarding.OnboardingScreen
import com.mxrider.sysmon.ui.screens.apphistory.AppHistoryScreen
import com.mxrider.sysmon.ui.screens.details.DetailsScreen
import com.mxrider.sysmon.ui.screens.performance.PerformanceScreen
import com.mxrider.sysmon.ui.screens.processes.ProcessesScreen
import com.mxrider.sysmon.ui.screens.services.ServicesScreen
import com.mxrider.sysmon.ui.screens.settings.SettingsScreen
import com.mxrider.sysmon.ui.screens.startup.StartupScreen
import com.mxrider.sysmon.ui.screens.users.UsersScreen
import com.mxrider.sysmon.ui.theme.FluentTheme
import kotlinx.coroutines.launch

@Composable
fun SysMonRoot() {
    // Nullable until DataStore's first disk read lands: seeding with DEFAULT
    // would flash the onboarding screen at every cold start for users who
    // already finished it (and seed `tab` from the wrong start page).
    val settings by produceState<SysMonSettings?>(null) {
        Graph.settings.settings.collect { value = it }
    }
    val scope = rememberCoroutineScope()

    val loaded = settings
    if (loaded == null) {
        FluentTheme(themeMode = ThemeMode.SYSTEM, accent = AccentOption.BLUE) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(FluentTheme.colors.window),
            )
        }
        return
    }

    FluentTheme(themeMode = loaded.themeMode, accent = loaded.accent) {
        if (!loaded.onboardingDone) {
            OnboardingScreen(
                onDone = { scope.launch { Graph.settings.setOnboardingDone(true) } },
            )
        } else {
            MainScaffold(loaded)
        }
    }
}

@Composable
private fun MainScaffold(settings: SysMonSettings) {
    var tab by rememberSaveable { mutableStateOf(settings.startPage) }
    var railExpanded by rememberSaveable { mutableStateOf(false) }

    KeepScreenOn(settings.keepScreenOn)

    Row(
        Modifier
            .fillMaxSize()
            .background(FluentTheme.colors.window)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        NavRail(
            selected = tab,
            onSelect = { tab = it; railExpanded = false },
            expanded = railExpanded,
            onToggleExpand = { railExpanded = !railExpanded },
        )
        Box(Modifier.fillMaxSize()) {
            Crossfade(targetState = tab, label = "tab") { destination ->
                when (destination) {
                    TabDestination.PROCESSES -> ProcessesScreen()
                    TabDestination.PERFORMANCE -> PerformanceScreen()
                    TabDestination.APP_HISTORY -> AppHistoryScreen()
                    TabDestination.STARTUP -> StartupScreen()
                    TabDestination.USERS -> UsersScreen()
                    TabDestination.DETAILS -> DetailsScreen()
                    TabDestination.SERVICES -> ServicesScreen()
                    TabDestination.SETTINGS -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
