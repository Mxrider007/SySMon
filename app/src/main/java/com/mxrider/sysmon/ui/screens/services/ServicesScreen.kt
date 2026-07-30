package com.mxrider.sysmon.ui.screens.services

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.ServiceRow
import com.mxrider.sysmon.ui.components.DataTable
import com.mxrider.sysmon.ui.components.EmptyState
import com.mxrider.sysmon.ui.components.FluentToggle
import com.mxrider.sysmon.ui.components.SearchBox
import com.mxrider.sysmon.ui.components.TableColumn
import com.mxrider.sysmon.ui.components.TitleBar
import com.mxrider.sysmon.ui.theme.FluentMetrics
import com.mxrider.sysmon.ui.theme.FluentTheme

/** Placeholder for missing values (no permission / no FGS types declared). */
private const val DASH = "—"

/**
 * Win11 Task Manager "Services" page, honestly adapted to Android: a sortable
 * inventory of services *declared* in installed apps' manifests. Android does
 * not expose live service state to third-party apps, so there is no
 * Running/Stopped column — the subtitle says exactly that.
 */
@Composable
fun ServicesScreen(modifier: Modifier = Modifier) {
    val vm: ServicesViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = FluentTheme.colors

    // The VM outlives the tab (Crossfade + activity-scoped): re-check the
    // inventory on each visit so installs/uninstalls eventually show up.
    LaunchedEffect(Unit) { vm.refreshIfStale() }

    val serviceTitle = stringResource(R.string.services_col_service)
    val appTitle = stringResource(R.string.services_col_app)
    val exportedTitle = stringResource(R.string.services_col_exported)
    val enabledTitle = stringResource(R.string.services_col_enabled)
    val fgsTitle = stringResource(R.string.services_col_fgs)
    val permissionTitle = stringResource(R.string.services_col_permission)
    val yes = stringResource(R.string.services_yes)
    val no = stringResource(R.string.services_no)

    val columns = remember(
        serviceTitle, appTitle, exportedTitle, enabledTitle, fgsTitle, permissionTitle, yes, no,
    ) {
        listOf(
            TableColumn<ServiceRow>(
                key = ServicesViewModel.COL_SERVICE,
                title = serviceTitle,
                width = 220.dp,
                sortable = true,
            ) { row -> ServiceCell(row) },
            TableColumn<ServiceRow>(
                key = ServicesViewModel.COL_APP,
                title = appTitle,
                width = 200.dp,
                sortable = true,
            ) { row -> AppCell(row) },
            TableColumn<ServiceRow>(
                key = ServicesViewModel.COL_EXPORTED,
                title = exportedTitle,
                width = 90.dp,
                sortable = true,
            ) { row -> CaptionCell(if (row.exported) yes else no) },
            TableColumn<ServiceRow>(
                key = ServicesViewModel.COL_ENABLED,
                title = enabledTitle,
                width = 80.dp,
                sortable = true,
            ) { row -> CaptionCell(if (row.enabled) yes else no) },
            TableColumn<ServiceRow>(
                key = ServicesViewModel.COL_FGS,
                title = fgsTitle,
                width = 180.dp,
                sortable = true,
            ) { row -> CaptionCell(row.foregroundServiceTypes.ifEmpty { DASH }) },
            TableColumn<ServiceRow>(
                key = ServicesViewModel.COL_PERMISSION,
                title = permissionTitle,
                width = 220.dp,
                sortable = true,
            ) { row -> CaptionCell(row.permission ?: DASH) },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(FluentMetrics.pagePadding),
    ) {
        TitleBar(
            title = stringResource(R.string.nav_services),
            subtitle = stringResource(R.string.services_subtitle),
            actions = {
                SearchBox(
                    query = state.query,
                    onQueryChange = vm::setQuery,
                    modifier = Modifier.width(220.dp),
                    placeholder = stringResource(R.string.action_search),
                )
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluentToggle(
                checked = state.hideSystem,
                onCheckedChange = vm::setHideSystem,
                label = stringResource(R.string.services_hide_system),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.services_hide_system),
                style = FluentTheme.typography.body,
                color = colors.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = pluralStringResource(
                    R.plurals.services_count_shown,
                    state.rows.size,
                    state.visibleRows.size,
                    state.rows.size,
                ),
                style = FluentTheme.typography.caption,
                color = colors.textSecondary,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp),
        ) {
            DataTable(
                columns = columns,
                rows = state.visibleRows,
                rowKey = { "${it.packageName}/${it.fqcn}" },
                modifier = Modifier.fillMaxSize(),
                sortKey = state.sortKey,
                ascending = state.ascending,
                onSortChange = vm::setSort,
            )
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentControl)
                }
            } else if (state.visibleRows.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.services_empty_title),
                    body = stringResource(R.string.services_empty_body),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun ServiceCell(row: ServiceRow) {
    Column {
        Text(
            text = row.serviceName,
            style = FluentTheme.typography.body,
            color = FluentTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = row.fqcn,
            style = FluentTheme.typography.caption,
            color = FluentTheme.colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppCell(row: ServiceRow) {
    Column {
        Text(
            text = row.appLabel,
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

@Composable
private fun CaptionCell(text: String) {
    Text(
        text = text,
        style = FluentTheme.typography.caption,
        color = FluentTheme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
