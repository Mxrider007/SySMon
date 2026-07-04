package com.mxrider.sysmon.ui.screens.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.AccentOption
import com.mxrider.sysmon.core.TabDestination
import com.mxrider.sysmon.core.ThemeMode
import com.mxrider.sysmon.core.UpdateSpeed
import com.mxrider.sysmon.service.CpuNotificationService
import com.mxrider.sysmon.ui.components.FluentCard
import com.mxrider.sysmon.ui.components.FluentDropDown
import com.mxrider.sysmon.ui.components.FluentStandardButton
import com.mxrider.sysmon.ui.components.FluentToggle
import com.mxrider.sysmon.ui.components.TitleBar
import com.mxrider.sysmon.ui.theme.FluentMetrics
import com.mxrider.sysmon.ui.theme.FluentTheme
import kotlinx.coroutines.launch

/**
 * Exodus Privacy home (opens in the browser). Their εxodus Android app audits
 * INSTALLED apps on-device — the correct verification path for a paid app;
 * exodus's web reports only crawl free Play apps, so a package-report URL
 * would 404 forever for SysMon.
 */
private const val EXODUS_URL = "https://exodus-privacy.eu.org/en/"

/** Support address; also shown verbatim in [R.string.settings_about_support]. */
private const val SUPPORT_MAILTO = "mailto:mxrider008@gmail.com?subject=SysMon%20support"

/** Win11-style settings list: grouped FluentCards of label + control rows. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = viewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val colors = FluentTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openFailedMessage = stringResource(R.string.settings_open_settings_failed)
    val openLinkFailedMessage = stringResource(R.string.settings_open_link_failed)
    val copiedMessage = stringResource(R.string.settings_diagnostics_copied)
    val noEmailMessage = stringResource(R.string.settings_no_email_app)
    val notifDeniedMessage = stringResource(R.string.settings_cpu_notification_denied)

    // API 33+ needs the runtime grant before the toggle may turn on; the
    // FGS itself would run without it, but its notification would be
    // invisible — a monitoring service nobody can see is not honest.
    val requestNotifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.setCpuNotification(true)
        } else {
            Toast.makeText(context, notifDeniedMessage, Toast.LENGTH_LONG).show()
        }
    }

    val themeLabels = mapOf(
        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
    )
    val speedLabels = mapOf(
        UpdateSpeed.HIGH to stringResource(R.string.settings_speed_high),
        UpdateSpeed.NORMAL to stringResource(R.string.settings_speed_normal),
        UpdateSpeed.LOW to stringResource(R.string.settings_speed_low),
        UpdateSpeed.PAUSED to stringResource(R.string.settings_speed_paused),
    )
    val tabLabels: Map<TabDestination, String> =
        TabDestination.entries.associateWith { stringResource(tabTitleRes(it)) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(FluentMetrics.pagePadding),
    ) {
        TitleBar(title = stringResource(R.string.nav_settings))
        Spacer(Modifier.height(16.dp))

        SectionHeader(stringResource(R.string.settings_section_appearance))
        FluentCard(Modifier.fillMaxWidth()) {
            SettingRow(label = stringResource(R.string.settings_theme)) {
                FluentDropDown(
                    options = ThemeMode.entries,
                    selected = settings.themeMode,
                    onSelect = vm::setThemeMode,
                    label = { themeLabels.getValue(it) },
                )
            }
            RowDivider()
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.settings_accent),
                    style = FluentTheme.typography.body,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccentOption.entries.forEach { option ->
                        AccentSwatch(
                            option = option,
                            selected = option == settings.accent,
                            name = accentName(option),
                            onSelect = { vm.setAccent(option) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        SectionHeader(stringResource(R.string.settings_section_behavior))
        FluentCard(Modifier.fillMaxWidth()) {
            SettingRow(label = stringResource(R.string.settings_update_speed)) {
                FluentDropDown(
                    options = UpdateSpeed.entries,
                    selected = settings.updateSpeed,
                    onSelect = vm::setUpdateSpeed,
                    label = { speedLabels.getValue(it) },
                )
            }
            RowDivider()
            SettingRow(label = stringResource(R.string.settings_start_page)) {
                FluentDropDown(
                    options = TabDestination.entries,
                    selected = settings.startPage,
                    onSelect = vm::setStartPage,
                    label = { tabLabels.getValue(it) },
                )
            }
            RowDivider()
            SettingRow(
                label = stringResource(R.string.settings_keep_screen_on),
                description = stringResource(R.string.settings_keep_screen_on_desc),
            ) {
                FluentToggle(
                    checked = settings.keepScreenOn,
                    onCheckedChange = vm::setKeepScreenOn,
                )
            }
            RowDivider()
            SettingRow(
                label = stringResource(R.string.settings_show_system_apps),
                description = stringResource(R.string.settings_show_system_apps_desc),
            ) {
                FluentToggle(
                    checked = settings.showSystemApps,
                    onCheckedChange = vm::setShowSystemApps,
                )
            }
            RowDivider()
            SettingRow(
                label = stringResource(R.string.settings_cpu_notification),
                description = stringResource(R.string.settings_cpu_notification_desc),
            ) {
                FluentToggle(
                    checked = settings.cpuNotification,
                    onCheckedChange = { checked ->
                        when {
                            !checked -> vm.setCpuNotification(false)
                            CpuNotificationService.canPostNotifications(context) ->
                                vm.setCpuNotification(true)
                            else -> requestNotifPermission.launch(
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        SectionHeader(stringResource(R.string.settings_section_about))
        FluentCard(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_about_name_version),
                style = FluentTheme.typography.subtitleSmall,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            AboutCaption(stringResource(R.string.settings_about_privacy))
            AboutCaption(stringResource(R.string.settings_about_font))
            AboutCaption(stringResource(R.string.settings_about_fluent))
            AboutCaption(stringResource(R.string.settings_about_usage_revoke))
            // The zero-tracker claim is externally checkable; opening the
            // report in the user's browser keeps SysMon itself internet-free.
            AboutLink(
                text = stringResource(R.string.settings_about_exodus),
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EXODUS_URL)))
                    } catch (_: Exception) {
                        Toast.makeText(context, openLinkFailedMessage, Toast.LENGTH_SHORT).show()
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            AboutCaption(stringResource(R.string.settings_diagnostics_desc))
            AboutLink(
                text = stringResource(R.string.settings_about_support),
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(SUPPORT_MAILTO)))
                    } catch (_: Exception) {
                        Toast.makeText(context, noEmailMessage, Toast.LENGTH_SHORT).show()
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            FluentStandardButton(
                text = stringResource(R.string.settings_usage_access_settings),
                onClick = {
                    try {
                        context.startActivity(Graph.usageAccess.settingsIntent())
                    } catch (_: Exception) {
                        Toast.makeText(context, openFailedMessage, Toast.LENGTH_SHORT).show()
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            FluentStandardButton(
                text = stringResource(R.string.settings_copy_diagnostics),
                onClick = {
                    scope.launch {
                        val report = Graph.diagnostics.buildReport()
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("SysMon diagnostics", report),
                        )
                        // Android 13+ shows its own "copied" overlay; a toast
                        // on top of it would double up.
                        if (Build.VERSION.SDK_INT <= 32) {
                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = FluentTheme.typography.bodyStrong,
        color = FluentTheme.colors.textPrimary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** Label (+ optional caption description) on the left, control right-aligned. */
@Composable
private fun SettingRow(
    label: String,
    description: String? = null,
    control: @Composable () -> Unit,
) {
    val colors = FluentTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 16.dp),
        ) {
            Text(
                text = label,
                style = FluentTheme.typography.body,
                color = colors.textPrimary,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = FluentTheme.typography.caption,
                    color = colors.textSecondary,
                )
            }
        }
        control()
    }
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FluentTheme.colors.divider),
    )
}

@Composable
private fun AboutCaption(text: String) {
    Text(
        text = text,
        style = FluentTheme.typography.caption,
        color = FluentTheme.colors.textSecondary,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

/** Accent-colored tappable caption row (external link / action). */
@Composable
private fun AboutLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = FluentTheme.typography.caption,
        color = FluentTheme.colors.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 2.dp),
    )
}

/**
 * 28dp accent circle, always filled with the light control color so swatches
 * look identical in both themes. Selected: 2dp focusStroke ring on the 36dp
 * touch target (leaving a gap around the swatch) + check glyph.
 */
@Composable
private fun AccentSwatch(
    option: AccentOption,
    selected: Boolean,
    name: String,
    onSelect: () -> Unit,
) {
    val colors = FluentTheme.colors
    Box(
        modifier = Modifier
            .size(36.dp)
            .then(
                if (selected) {
                    Modifier.border(2.dp, colors.focusStroke, CircleShape)
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(Color(option.controlLightArgb), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    // The fill is always the light-mode control color, whose
                    // validated foreground is white; onAccentControl is black
                    // in dark theme and would vanish on these swatches.
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun accentName(option: AccentOption): String = stringResource(
    when (option) {
        AccentOption.BLUE -> R.string.settings_accent_blue
        AccentOption.PURPLE -> R.string.settings_accent_purple
        AccentOption.TEAL -> R.string.settings_accent_teal
        AccentOption.GREEN -> R.string.settings_accent_green
        AccentOption.ORANGE -> R.string.settings_accent_orange
        AccentOption.RED -> R.string.settings_accent_red
    },
)

private fun tabTitleRes(tab: TabDestination): Int = when (tab) {
    TabDestination.PROCESSES -> R.string.nav_processes
    TabDestination.PERFORMANCE -> R.string.nav_performance
    TabDestination.APP_HISTORY -> R.string.nav_app_history
    TabDestination.STARTUP -> R.string.nav_startup
    TabDestination.USERS -> R.string.nav_users
    TabDestination.DETAILS -> R.string.nav_details
    TabDestination.SERVICES -> R.string.nav_services
    TabDestination.SETTINGS -> R.string.nav_settings
}
