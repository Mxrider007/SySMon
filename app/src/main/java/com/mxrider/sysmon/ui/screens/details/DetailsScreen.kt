package com.mxrider.sysmon.ui.screens.details

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.AppEntry
import com.mxrider.sysmon.core.Format
import com.mxrider.sysmon.ui.components.AppIcon
import com.mxrider.sysmon.ui.components.DataTable
import com.mxrider.sysmon.ui.components.EmptyState
import com.mxrider.sysmon.ui.components.FluentCard
import com.mxrider.sysmon.ui.components.FluentStandardButton
import com.mxrider.sysmon.ui.components.SearchBox
import com.mxrider.sysmon.ui.components.StatTile
import com.mxrider.sysmon.ui.components.TableColumn
import com.mxrider.sysmon.ui.components.TitleBar
import com.mxrider.sysmon.ui.theme.FluentMetrics
import com.mxrider.sysmon.ui.theme.FluentTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Placeholder for missing values ([Format.bytes] convention). */
private const val DASH = "—"

/**
 * Win11 Task Manager "Details" page: a dense, sortable inventory of every
 * installed package. The search box filters on label + package name; selecting
 * a row reveals a bottom detail strip with key facts and an App info deep link.
 */
@Composable
fun DetailsScreen(modifier: Modifier = Modifier) {
    val vm: DetailsViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = FluentTheme.colors

    // The VM outlives the tab (Crossfade + activity-scoped): re-check the
    // inventory on each visit so installs/uninstalls eventually show up.
    LaunchedEffect(Unit) { vm.refreshIfStale() }

    val selectedRow = remember(state.visibleRows, state.selectedPkg) {
        state.visibleRows.firstOrNull { it.packageName == state.selectedPkg }
    }

    // SHORT locale date for the Installed/Updated columns. Only ever touched
    // from the composition (main thread), so the shared instance is safe.
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.SHORT) }

    val appTitle = stringResource(R.string.details_col_app)
    val packageTitle = stringResource(R.string.details_col_package)
    val versionTitle = stringResource(R.string.details_col_version)
    val targetSdkTitle = stringResource(R.string.details_col_target_sdk)
    val minSdkTitle = stringResource(R.string.details_col_min_sdk)
    val sizeTitle = stringResource(R.string.details_col_size)
    val installedTitle = stringResource(R.string.details_col_installed)
    val updatedTitle = stringResource(R.string.details_col_updated)
    val installerTitle = stringResource(R.string.details_col_installer)
    val enabledTitle = stringResource(R.string.details_col_enabled)
    val yes = stringResource(R.string.details_enabled_yes)
    val no = stringResource(R.string.details_enabled_no)

    val columns = remember(
        appTitle, packageTitle, versionTitle, targetSdkTitle, minSdkTitle,
        sizeTitle, installedTitle, updatedTitle, installerTitle, enabledTitle,
        yes, no,
    ) {
        listOf(
            TableColumn<AppEntry>(
                key = DetailsViewModel.COL_APP,
                title = appTitle,
                width = 240.dp,
                sortable = true,
            ) { row -> AppCell(row) },
            TableColumn<AppEntry>(
                key = DetailsViewModel.COL_PACKAGE,
                title = packageTitle,
                width = 220.dp,
                sortable = true,
            ) { row -> CaptionCell(row.packageName) },
            TableColumn<AppEntry>(
                key = DetailsViewModel.COL_VERSION,
                title = versionTitle,
                width = 110.dp,
                sortable = true,
            ) { row -> BodyCell(row.versionName ?: DASH) },
            TableColumn<AppEntry>(
                key = DetailsViewModel.COL_TARGET_SDK,
                title = targetSdkTitle,
                width = 90.dp,
                alignEnd = true,
                sortable = true,
            ) { row -> BodyCell(row.targetSdk.toString()) },
            TableColumn<AppEntry>(
                key = DetailsViewModel.COL_MIN_SDK,
                title = minSdkTitle,
                width = 80.dp,
                alignEnd = true,
                sortable = true,
            ) { row -> BodyCell(row.minSdk.toString()) },
            TableColumn<AppEntry>(
                key = DetailsViewModel.COL_SIZE,
                title = sizeTitle,
                width = 90.dp,
                alignEnd = true,
                sortable = true,
            ) { row -> BodyCell(Format.bytes(row.apkSizeBytes)) },
            TableColumn<AppEntry>(
                key = DetailsViewModel.COL_INSTALLED,
                title = installedTitle,
                width = 110.dp,
                alignEnd = true,
                sortable = true,
            ) { row -> BodyCell(formatDate(dateFormat, row.installTime)) },
            TableColumn<AppEntry>(
                key = DetailsViewModel.COL_UPDATED,
                title = updatedTitle,
                width = 110.dp,
                alignEnd = true,
                sortable = true,
            ) { row -> BodyCell(formatDate(dateFormat, row.updateTime)) },
            TableColumn<AppEntry>(
                key = DetailsViewModel.COL_INSTALLER,
                title = installerTitle,
                width = 140.dp,
                sortable = true,
            ) { row -> CaptionCell(row.installerPackage ?: DASH) },
            TableColumn<AppEntry>(
                key = DetailsViewModel.COL_ENABLED,
                title = enabledTitle,
                width = 80.dp,
                sortable = true,
            ) { row -> BodyCell(if (row.enabled) yes else no) },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(FluentMetrics.pagePadding),
    ) {
        val scope = rememberCoroutineScope()
        val exportFailed = stringResource(R.string.details_export_failed)
        TitleBar(
            title = stringResource(R.string.nav_details),
            actions = {
                SearchBox(
                    query = state.query,
                    onQueryChange = vm::setQuery,
                    modifier = Modifier.width(220.dp),
                    placeholder = stringResource(R.string.action_search),
                )
                Spacer(Modifier.width(8.dp))
                FluentStandardButton(
                    text = stringResource(R.string.details_export_csv),
                    onClick = {
                        // Exports what the table currently shows — filter and
                        // sort included — so the file always matches the screen.
                        val rows = state.visibleRows
                        scope.launch {
                            val chooser = DetailsCsv.buildShareIntent(context, rows)
                            if (chooser != null) {
                                try {
                                    context.startActivity(chooser)
                                } catch (e: Exception) {
                                    Toast.makeText(context, exportFailed, Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, exportFailed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            },
        )

        Text(
            text = stringResource(R.string.details_count_shown, state.visibleRows.size, state.rows.size),
            style = FluentTheme.typography.caption,
            color = colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp),
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
                    title = stringResource(R.string.details_empty_title),
                    body = stringResource(R.string.details_empty_body),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            }
        }

        if (selectedRow != null) {
            DetailStrip(
                row = selectedRow,
                onAppInfo = {
                    try {
                        context.startActivity(Graph.taskKiller.appInfoIntent(selectedRow.packageName))
                    } catch (e: Exception) {
                        // Settings screen unavailable on this device; ignore.
                    }
                },
            )
        }
    }
}

/** Bottom strip for the selected row: key facts + App info deep link. */
@Composable
private fun DetailStrip(row: AppEntry, onAppInfo: () -> Unit) {
    FluentCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatTile(
                    label = stringResource(R.string.details_strip_package),
                    value = row.packageName,
                )
                StatTile(
                    label = stringResource(R.string.details_strip_version),
                    value = row.versionName ?: DASH,
                )
                StatTile(
                    label = stringResource(R.string.details_strip_uid),
                    value = row.uid.toString(),
                )
                StatTile(
                    label = stringResource(R.string.details_strip_installer),
                    value = row.installerPackage ?: DASH,
                )
            }
            Spacer(Modifier.width(16.dp))
            FluentStandardButton(
                text = stringResource(R.string.action_app_info),
                onClick = onAppInfo,
            )
        }
    }
}

@Composable
private fun AppCell(row: AppEntry) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(
            packageName = row.packageName,
            size = 20.dp,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = row.label,
            style = FluentTheme.typography.body,
            color = if (row.enabled) FluentTheme.colors.textPrimary else FluentTheme.colors.textDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BodyCell(text: String) {
    Text(
        text = text,
        style = FluentTheme.typography.body,
        color = FluentTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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

private fun formatDate(format: DateFormat, epochMillis: Long): String =
    if (epochMillis <= 0) DASH else format.format(Date(epochMillis))

