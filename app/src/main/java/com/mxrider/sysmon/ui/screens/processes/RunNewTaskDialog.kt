package com.mxrider.sysmon.ui.screens.processes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.AppEntry
import com.mxrider.sysmon.ui.components.FluentDialog
import com.mxrider.sysmon.ui.components.SearchBox
import com.mxrider.sysmon.ui.theme.FluentMetrics
import com.mxrider.sysmon.ui.theme.FluentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LIST_HEIGHT = 280.dp

/**
 * Win11 "Run new task" as it can exist on Android: a searchable list of
 * launchable apps; tapping one starts its launch intent and dismisses.
 * Apps are listed on IO with a spinner while loading.
 */
@Composable
fun RunNewTaskDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = FluentTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            try {
                Graph.apps.installedApps()
                    .filter { entry ->
                        try {
                            Graph.taskKiller.launchIntent(entry.packageName) != null
                        } catch (e: Exception) {
                            false
                        }
                    }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            } catch (e: Exception) {
                emptyList<AppEntry>()
            }
        }
    }

    FluentDialog(
        title = stringResource(R.string.action_run_new_task),
        onDismiss = onDismiss,
        confirmText = stringResource(R.string.action_ok),
        onConfirm = onDismiss,
        body = stringResource(R.string.processes_run_new_task_body),
    ) {
        Spacer(Modifier.height(12.dp))
        SearchBox(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.action_search),
        )
        Spacer(Modifier.height(8.dp))

        val loaded = apps
        if (loaded == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LIST_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = colors.accentControl)
            }
        } else {
            val filtered = remember(loaded, query) {
                val q = query.trim()
                if (q.isEmpty()) {
                    loaded
                } else {
                    loaded.filter {
                        it.label.contains(q, ignoreCase = true) ||
                            it.packageName.contains(q, ignoreCase = true)
                    }
                }
            }
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LIST_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.processes_run_new_task_empty),
                        style = FluentTheme.typography.body,
                        color = colors.textSecondary,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LIST_HEIGHT),
                ) {
                    items(items = filtered, key = { it.packageName }) { app ->
                        LaunchableAppRow(
                            app = app,
                            onClick = {
                                try {
                                    val intent = Graph.taskKiller.launchIntent(app.packageName)
                                    if (intent != null) context.startActivity(intent)
                                } catch (e: Exception) {
                                    // Activity gone or blocked by the OEM; ignore.
                                }
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchableAppRow(app: AppEntry, onClick: () -> Unit) {
    val colors = FluentTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluentMetrics.controlCorner))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProcessAppIcon(
            packageName = app.packageName,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = FluentTheme.typography.body,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = FluentTheme.typography.caption,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
