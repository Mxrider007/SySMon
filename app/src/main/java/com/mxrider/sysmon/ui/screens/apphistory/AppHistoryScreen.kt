package com.mxrider.sysmon.ui.screens.apphistory

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.Format
import com.mxrider.sysmon.core.UsageRange
import com.mxrider.sysmon.core.UsageRow
import com.mxrider.sysmon.ui.components.DataTable
import com.mxrider.sysmon.ui.components.EmptyState
import com.mxrider.sysmon.ui.components.FluentDropDown
import com.mxrider.sysmon.ui.components.TableColumn
import com.mxrider.sysmon.ui.components.TitleBar
import com.mxrider.sysmon.ui.theme.FluentMetrics
import com.mxrider.sysmon.ui.theme.FluentTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Placeholder for values that need usage access ([Format.bytes] convention). */
private const val DASH = "—"

/**
 * Win11 Task Manager "App history" page: a range combo box (Today / 7 days /
 * 30 days) and a sortable table of per-app screen time, launches, Wi-Fi and
 * mobile data, and last-used time. Without usage access the table is replaced
 * by a grant gate that polls until the permission arrives.
 */
@Composable
fun AppHistoryScreen(modifier: Modifier = Modifier) {
    val vm: AppHistoryViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val colors = FluentTheme.colors

    val sinceDate = remember(state.rangeStartMillis) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(state.rangeStartMillis))
    }

    val rangeToday = stringResource(R.string.apphistory_range_today)
    val range7Days = stringResource(R.string.apphistory_range_7_days)
    val range30Days = stringResource(R.string.apphistory_range_30_days)

    val appTitle = stringResource(R.string.apphistory_col_app)
    val screenTimeTitle = stringResource(R.string.apphistory_col_screen_time)
    val launchesTitle = stringResource(R.string.apphistory_col_launches)
    val wifiTitle = stringResource(R.string.apphistory_col_wifi)
    val mobileTitle = stringResource(R.string.apphistory_col_mobile)
    val lastUsedTitle = stringResource(R.string.apphistory_col_last_used)

    val columns = remember(
        appTitle, screenTimeTitle, launchesTitle, wifiTitle, mobileTitle, lastUsedTitle,
    ) {
        listOf(
            TableColumn<UsageRow>(
                key = AppHistoryViewModel.COL_APP,
                title = appTitle,
                width = 240.dp,
                comparator = AppHistoryViewModel.COMPARATOR_APP,
            ) { row -> AppCell(row) },
            TableColumn<UsageRow>(
                key = AppHistoryViewModel.COL_SCREEN_TIME,
                title = screenTimeTitle,
                width = 120.dp,
                alignEnd = true,
                comparator = AppHistoryViewModel.COMPARATOR_SCREEN_TIME,
            ) { row -> ValueCell(Format.duration(row.screenTimeMs)) },
            TableColumn<UsageRow>(
                key = AppHistoryViewModel.COL_LAUNCHES,
                title = launchesTitle,
                width = 90.dp,
                alignEnd = true,
                comparator = AppHistoryViewModel.COMPARATOR_LAUNCHES,
            ) { row -> ValueCell(row.launches.toString()) },
            TableColumn<UsageRow>(
                key = AppHistoryViewModel.COL_WIFI,
                title = wifiTitle,
                width = 110.dp,
                alignEnd = true,
                comparator = AppHistoryViewModel.COMPARATOR_WIFI,
            ) { row -> ValueCell(row.wifiBytes?.let { Format.bytes(it) } ?: DASH) },
            TableColumn<UsageRow>(
                key = AppHistoryViewModel.COL_MOBILE,
                title = mobileTitle,
                width = 110.dp,
                alignEnd = true,
                comparator = AppHistoryViewModel.COMPARATOR_MOBILE,
            ) { row -> ValueCell(row.mobileBytes?.let { Format.bytes(it) } ?: DASH) },
            TableColumn<UsageRow>(
                key = AppHistoryViewModel.COL_LAST_USED,
                title = lastUsedTitle,
                width = 130.dp,
                comparator = AppHistoryViewModel.COMPARATOR_LAST_USED,
            ) { row -> ValueCell(Format.relativeTime(row.lastUsed)) },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(FluentMetrics.pagePadding),
    ) {
        TitleBar(
            title = stringResource(R.string.nav_app_history),
            subtitle = stringResource(R.string.apphistory_subtitle, sinceDate),
            actions = {
                FluentDropDown(
                    options = UsageRange.entries,
                    selected = range,
                    onSelect = vm::setRange,
                    label = { option ->
                        when (option) {
                            UsageRange.TODAY -> rangeToday
                            UsageRange.DAYS_7 -> range7Days
                            UsageRange.DAYS_30 -> range30Days
                        }
                    },
                )
            },
        )

        Text(
            text = stringResource(R.string.apphistory_note),
            style = FluentTheme.typography.caption,
            color = colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 12.dp),
        ) {
            if (!state.usageAccessGranted) {
                UsageAccessGate(
                    onRecheck = vm::recheckUsageAccess,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            } else {
                DataTable(
                    columns = columns,
                    rows = state.visibleRows,
                    rowKey = { it.packageName },
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
                        title = stringResource(R.string.apphistory_empty_title),
                        body = stringResource(R.string.apphistory_empty_body),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        icon = Icons.Outlined.History,
                    )
                }
            }
        }
    }
}

/**
 * Usage-access gate: explains what the grant unlocks and polls [onRecheck]
 * every 2s so the table appears as soon as the permission is granted.
 */
@Composable
private fun UsageAccessGate(onRecheck: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000L)
            onRecheck()
        }
    }
    EmptyState(
        title = stringResource(R.string.apphistory_gate_title),
        body = stringResource(R.string.apphistory_gate_body),
        modifier = modifier,
        icon = Icons.Outlined.QueryStats,
        actionText = stringResource(R.string.apphistory_gate_action),
        onAction = {
            try {
                context.startActivity(Graph.usageAccess.settingsIntent())
            } catch (e: Exception) {
                // Usage access settings screen missing on this OEM; ignore.
            }
        },
    )
}

@Composable
private fun AppCell(row: UsageRow) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HistoryAppIcon(
            packageName = row.packageName,
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
private fun ValueCell(text: String) {
    Text(
        text = text,
        style = FluentTheme.typography.body,
        color = FluentTheme.colors.textPrimary,
        maxLines = 1,
    )
}

/**
 * App icon loaded off the main thread from PackageManager (no image-loading
 * library in this project). Falls back to a tinted generic icon while loading
 * or when the package has no icon.
 */
@Composable
private fun HistoryAppIcon(
    packageName: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val icon = remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        icon.value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(40, 40)
                    .asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    val bitmap = icon.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
        )
    } else {
        Icon(
            imageVector = Icons.Outlined.Android,
            contentDescription = contentDescription,
            tint = FluentTheme.colors.textTertiary,
            modifier = modifier,
        )
    }
}
