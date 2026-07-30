package com.mxrider.sysmon.ui.screens.startup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.StartupImpact
import com.mxrider.sysmon.core.StartupRow
import com.mxrider.sysmon.ui.components.AppIcon
import com.mxrider.sysmon.ui.components.DataTable
import com.mxrider.sysmon.ui.components.EmptyState
import com.mxrider.sysmon.ui.components.FluentStandardButton
import com.mxrider.sysmon.ui.components.TableColumn
import com.mxrider.sysmon.ui.components.TitleBar
import com.mxrider.sysmon.ui.theme.FluentMetrics
import com.mxrider.sysmon.ui.theme.FluentTheme

/**
 * Win11 Task Manager "Startup apps" page: sortable table of apps that
 * register BOOT_COMPLETED receivers, with an enabled/disabled status, the
 * receiver list and a heuristic startup-impact caption. Android offers no
 * toggle to third-party apps, so the row action deep-links to App info where
 * the user can disable the app.
 */
@Composable
fun StartupScreen(modifier: Modifier = Modifier) {
    val vm: StartupViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = FluentTheme.colors

    val selectedRow = remember(state.visibleRows, state.selectedPkg) {
        state.visibleRows.firstOrNull { it.packageName == state.selectedPkg }
    }

    val appTitle = stringResource(R.string.startup_col_app)
    val statusTitle = stringResource(R.string.startup_col_status)
    val receiversTitle = stringResource(R.string.startup_col_receivers)
    val impactTitle = stringResource(R.string.startup_col_impact)

    val columns = remember(appTitle, statusTitle, receiversTitle, impactTitle) {
        listOf(
            TableColumn<StartupRow>(
                key = StartupViewModel.COL_APP,
                title = appTitle,
                width = 260.dp,
                sortable = true,
            ) { row -> AppCell(row) },
            TableColumn<StartupRow>(
                key = StartupViewModel.COL_STATUS,
                title = statusTitle,
                width = 100.dp,
                sortable = true,
            ) { row -> StatusCell(row.enabled) },
            TableColumn<StartupRow>(
                key = StartupViewModel.COL_RECEIVERS,
                title = receiversTitle,
                width = 200.dp,
                sortable = true,
            ) { row -> ReceiversCell(row) },
            TableColumn<StartupRow>(
                key = StartupViewModel.COL_IMPACT,
                title = impactTitle,
                width = 130.dp,
                sortable = true,
            ) { row -> ImpactCell(row.impact) },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(FluentMetrics.pagePadding),
    ) {
        TitleBar(
            title = stringResource(R.string.nav_startup),
            subtitle = stringResource(R.string.startup_subtitle),
            actions = {
                FluentStandardButton(
                    text = stringResource(R.string.action_refresh),
                    onClick = vm::refresh,
                )
            },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 12.dp),
        ) {
            DataTable(
                columns = columns,
                rows = state.visibleRows,
                rowKey = { it.packageName },
                modifier = Modifier.fillMaxSize(),
                sortKey = state.sortKey,
                ascending = state.ascending,
                onSortChange = vm::setSort,
                selectedKey = state.selectedPkg,
                onSelect = { vm.select(it?.packageName) },
            )
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentControl)
                }
            } else if (state.visibleRows.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.startup_empty_title),
                    body = stringResource(R.string.startup_empty_body),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    icon = Icons.Outlined.RocketLaunch,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.startup_count,
                    state.visibleRows.size,
                    state.visibleRows.size,
                ),
                style = FluentTheme.typography.caption,
                color = colors.textSecondary,
            )
            Spacer(Modifier.weight(1f))
            FluentStandardButton(
                text = stringResource(R.string.action_app_info),
                onClick = {
                    selectedRow?.let { row ->
                        try {
                            context.startActivity(Graph.taskKiller.appInfoIntent(row.packageName))
                        } catch (e: Exception) {
                            // Settings screen unavailable on this device; ignore.
                        }
                    }
                },
                enabled = selectedRow != null,
            )
        }
    }
}

@Composable
private fun AppCell(row: StartupRow) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(
            packageName = row.packageName,
            size = 20.dp,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = row.label,
                style = FluentTheme.typography.body,
                color = FluentTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.packageName,
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusCell(enabled: Boolean) {
    Text(
        text = stringResource(
            if (enabled) R.string.startup_status_enabled else R.string.startup_status_disabled,
        ),
        style = FluentTheme.typography.caption,
        color = FluentTheme.colors.textSecondary,
        maxLines = 1,
    )
}

/** Receiver count with the (ellipsized) class names as a caption second line. */
@Composable
private fun ReceiversCell(row: StartupRow) {
    Column {
        Text(
            text = pluralStringResource(
                R.plurals.startup_receivers_count,
                row.receiverNames.size,
                row.receiverNames.size,
            ),
            style = FluentTheme.typography.body,
            color = FluentTheme.colors.textPrimary,
            maxLines = 1,
        )
        Text(
            text = row.receiverNames.joinToString(", "),
            style = FluentTheme.typography.caption,
            color = FluentTheme.colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Informational impact caption in textSecondary — deliberately not
 * color-coded with status colors (it is not a good/bad state).
 */
@Composable
private fun ImpactCell(impact: StartupImpact) {
    Text(
        text = stringResource(
            when (impact) {
                StartupImpact.LOW -> R.string.startup_impact_low
                StartupImpact.MEDIUM -> R.string.startup_impact_medium
                StartupImpact.HIGH -> R.string.startup_impact_high
            },
        ),
        style = FluentTheme.typography.caption,
        color = FluentTheme.colors.textSecondary,
        maxLines = 1,
    )
}

