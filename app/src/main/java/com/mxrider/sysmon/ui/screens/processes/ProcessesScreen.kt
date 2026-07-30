package com.mxrider.sysmon.ui.screens.processes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.Format
import com.mxrider.sysmon.core.ProcStatus
import com.mxrider.sysmon.core.ProcessRow
import com.mxrider.sysmon.ui.components.DataTable
import com.mxrider.sysmon.ui.components.EmptyState
import com.mxrider.sysmon.ui.components.FluentButton
import com.mxrider.sysmon.ui.components.FluentDialog
import com.mxrider.sysmon.ui.components.FluentStandardButton
import com.mxrider.sysmon.ui.components.FluentToggle
import com.mxrider.sysmon.ui.components.SearchBox
import com.mxrider.sysmon.ui.components.TableColumn
import com.mxrider.sysmon.ui.components.TitleBar
import com.mxrider.sysmon.ui.theme.FluentMetrics
import com.mxrider.sysmon.ui.theme.FluentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Placeholder for values that need usage access ([Format.bytes] convention). */
private const val DASH = "—"

/**
 * Win11 Task Manager "Processes" page: toolbar (search + Run new task),
 * "Show system processes" toggle, sortable table of today's app activity and
 * a bottom bar with App info / End task. Without usage access the table
 * degrades to installed apps and a grant gate is shown above it.
 */
@Composable
fun ProcessesScreen(modifier: Modifier = Modifier) {
    val vm: ProcessesViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = FluentTheme.colors

    var showRunNewTask by rememberSaveable { mutableStateOf(false) }
    var showEndTaskConfirm by rememberSaveable { mutableStateOf(false) }

    val selectedRow = remember(state.visibleRows, state.selectedPkg) {
        state.visibleRows.firstOrNull { it.packageName == state.selectedPkg }
    }

    val appTitle = stringResource(R.string.processes_col_app)
    val statusTitle = stringResource(R.string.processes_col_status)
    val screenTimeTitle = stringResource(R.string.processes_col_screen_time)
    val networkTitle = stringResource(R.string.processes_col_network)
    val storageTitle = stringResource(R.string.processes_col_storage)

    val columns = remember(appTitle, statusTitle, screenTimeTitle, networkTitle, storageTitle) {
        listOf(
            TableColumn<ProcessRow>(
                key = ProcessesViewModel.COL_APP,
                title = appTitle,
                width = 240.dp,
                sortable = true,
            ) { row -> AppCell(row) },
            TableColumn<ProcessRow>(
                key = ProcessesViewModel.COL_STATUS,
                title = statusTitle,
                width = 90.dp,
                sortable = true,
            ) { row -> StatusCell(row.status) },
            TableColumn<ProcessRow>(
                key = ProcessesViewModel.COL_SCREEN_TIME,
                title = screenTimeTitle,
                width = 110.dp,
                alignEnd = true,
                sortable = true,
                // UNKNOWN (no usage access) renders a dash like the other
                // gated columns — "0:00" would be a fabricated measurement.
            ) { row ->
                ValueCell(
                    if (row.status == ProcStatus.UNKNOWN) DASH
                    else Format.duration(row.foregroundTimeTodayMs),
                )
            },
            TableColumn<ProcessRow>(
                key = ProcessesViewModel.COL_NETWORK,
                title = networkTitle,
                width = 110.dp,
                alignEnd = true,
                sortable = true,
            ) { row -> ValueCell(row.networkTodayBytes?.let { Format.bytes(it) } ?: DASH) },
            TableColumn<ProcessRow>(
                key = ProcessesViewModel.COL_STORAGE,
                title = storageTitle,
                width = 110.dp,
                alignEnd = true,
                sortable = true,
            ) { row -> ValueCell(row.storageBytes?.let { Format.bytes(it) } ?: DASH) },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(FluentMetrics.pagePadding),
    ) {
        TitleBar(
            title = stringResource(R.string.nav_processes),
            actions = {
                SearchBox(
                    query = state.query,
                    onQueryChange = vm::setQuery,
                    // Flexible: on narrow panes the toolbar shares one row with
                    // the button and a rigid 220dp would clip it.
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 220.dp),
                    placeholder = stringResource(R.string.action_search),
                )
                FluentStandardButton(
                    text = stringResource(R.string.action_run_new_task),
                    onClick = { showRunNewTask = true },
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
                checked = state.showSystem,
                onCheckedChange = vm::setShowSystem,
                label = stringResource(R.string.processes_show_system),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.processes_show_system),
                style = FluentTheme.typography.body,
                color = colors.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(
                    R.string.processes_count_shown,
                    state.visibleRows.size,
                    state.rows.size,
                ),
                style = FluentTheme.typography.caption,
                color = colors.textSecondary,
            )
        }

        if (!state.usageAccessGranted) {
            UsageAccessGate(onRecheck = vm::recheckUsageAccess)
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
                rowKey = { it.packageName },
                modifier = Modifier.fillMaxSize(),
                sortKey = state.sortKey,
                ascending = state.ascending,
                onSortChange = vm::setSort,
                selectedKey = state.selectedPkg,
                onSelect = {
                    vm.select(it?.packageName)
                    showEndTaskConfirm = false
                },
            )
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentControl)
                }
            } else if (state.visibleRows.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.processes_empty_title),
                    body = stringResource(R.string.processes_empty_body),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            }
        }

        state.infoMessage?.let { info -> InfoMessageBar(info) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.processes_footer,
                    state.visibleRows.size,
                    stringResource(
                        if (state.usageAccessGranted) {
                            R.string.processes_access_yes
                        } else {
                            R.string.processes_access_no
                        },
                    ),
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
            FluentButton(
                text = stringResource(R.string.action_end_task),
                onClick = { showEndTaskConfirm = true },
                enabled = selectedRow != null,
                danger = true,
            )
        }
    }

    if (showRunNewTask) {
        RunNewTaskDialog(onDismiss = { showRunNewTask = false })
    }

    if (showEndTaskConfirm && selectedRow != null) {
        val canKill = remember { Graph.taskKiller.canKill() }
        if (canKill) {
            FluentDialog(
                title = stringResource(R.string.processes_end_task_title, selectedRow.label),
                onDismiss = { showEndTaskConfirm = false },
                confirmText = stringResource(R.string.action_end_task),
                onConfirm = {
                    showEndTaskConfirm = false
                    vm.endTask(selectedRow)
                },
                body = stringResource(R.string.processes_end_task_body),
                danger = true,
            )
        } else {
            // Android 14+ silently ignores background-kill requests for other
            // apps, so the primary action routes to the system App info screen,
            // where Force stop genuinely works — never a button that does nothing.
            FluentDialog(
                title = stringResource(R.string.processes_end_task_title, selectedRow.label),
                onDismiss = { showEndTaskConfirm = false },
                confirmText = stringResource(R.string.action_open_app_info),
                onConfirm = {
                    showEndTaskConfirm = false
                    try {
                        context.startActivity(Graph.taskKiller.appInfoIntent(selectedRow.packageName))
                    } catch (e: Exception) {
                        // Settings screen unavailable on this device; ignore.
                    }
                },
                body = stringResource(R.string.processes_end_task_body_limited),
            )
        }
    }
}

/**
 * Usage-access gate: explains what the grant unlocks and polls
 * [onRecheck] every 2s so the gate dismisses itself once granted.
 * The (degraded) table stays visible beneath the caption notice.
 */
@Composable
private fun UsageAccessGate(onRecheck: () -> Unit) {
    val context = LocalContext.current
    // repeatOnLifecycle: a plain delay-loop LaunchedEffect keeps polling the
    // AppOps binder every 2s even while the activity is stopped.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(2_000L)
                onRecheck()
            }
        }
    }
    EmptyState(
        title = stringResource(R.string.processes_gate_title),
        body = stringResource(R.string.processes_gate_body),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 16.dp),
        icon = Icons.Outlined.QueryStats,
        actionText = stringResource(R.string.processes_gate_action),
        onAction = {
            try {
                context.startActivity(Graph.usageAccess.settingsIntent())
            } catch (e: Exception) {
                // Usage access settings screen missing on this OEM; ignore.
            }
        },
    )
    Text(
        text = stringResource(R.string.processes_gate_notice),
        style = FluentTheme.typography.caption,
        color = FluentTheme.colors.textTertiary,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** InfoBar-style transient message ("Requested background kill of X"). */
@Composable
private fun InfoMessageBar(info: ProcessesViewModel.InfoMessage) {
    val colors = FluentTheme.colors
    val shape = RoundedCornerShape(FluentMetrics.controlCorner)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(colors.card, shape)
            .border(1.dp, colors.cardStroke, shape)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = when (info.kind) {
                ProcessesViewModel.InfoKind.KILL_REQUESTED ->
                    stringResource(R.string.processes_kill_requested, info.appLabel)
                ProcessesViewModel.InfoKind.KILL_UNSUPPORTED ->
                    stringResource(R.string.processes_kill_unsupported, info.appLabel)
            },
            style = FluentTheme.typography.caption,
            color = colors.textPrimary,
        )
    }
}

@Composable
private fun AppCell(row: ProcessRow) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProcessAppIcon(
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
private fun StatusCell(status: ProcStatus) {
    Text(
        text = when (status) {
            ProcStatus.ACTIVE -> stringResource(R.string.processes_status_running)
            ProcStatus.RECENT -> stringResource(R.string.processes_status_recent)
            ProcStatus.CACHED -> stringResource(R.string.processes_status_cached)
            ProcStatus.INACTIVE -> stringResource(R.string.processes_status_not_running)
            // Without usage access the state is unknowable; claiming
            // "Not running" would contradict the empty-state caption.
            ProcStatus.UNKNOWN -> DASH
        },
        style = FluentTheme.typography.caption,
        color = FluentTheme.colors.textSecondary,
        maxLines = 1,
    )
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
internal fun ProcessAppIcon(
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
