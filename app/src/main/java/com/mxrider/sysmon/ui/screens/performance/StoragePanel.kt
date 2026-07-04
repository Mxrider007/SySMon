package com.mxrider.sysmon.ui.screens.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.Format
import com.mxrider.sysmon.core.VolumeInfo
import com.mxrider.sysmon.ui.components.EmptyState
import com.mxrider.sysmon.ui.components.FluentCard
import com.mxrider.sysmon.ui.components.StatTile
import com.mxrider.sysmon.ui.components.chart.BarSegment
import com.mxrider.sysmon.ui.components.chart.CompositionBar
import com.mxrider.sysmon.ui.theme.FluentTheme

@Composable
internal fun StoragePanel(
    state: PerfUiState,
    modifier: Modifier = Modifier,
) {
    val colors = FluentTheme.colors
    val typography = FluentTheme.typography
    val storage = state.storage

    Column(modifier.fillMaxWidth()) {
        PanelHeader(title = stringResource(R.string.perf_tile_storage))
        Spacer(Modifier.height(12.dp))
        when {
            storage == null -> Text(
                text = stringResource(R.string.label_loading),
                style = typography.body,
                color = colors.textSecondary,
            )
            storage.volumes.isEmpty() -> EmptyState(
                title = stringResource(R.string.perf_storage_empty_title),
                body = stringResource(R.string.perf_storage_empty_body),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                icon = Icons.Filled.Storage,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                storage.volumes.forEach { volume ->
                    VolumeCard(volume)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VolumeCard(volume: VolumeInfo) {
    val colors = FluentTheme.colors
    val typography = FluentTheme.typography
    FluentCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = volume.label,
                style = typography.subtitleSmall,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (volume.isRemovable) {
                Text(
                    text = stringResource(R.string.perf_storage_removable),
                    style = typography.caption,
                    color = colors.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        val fraction = if (volume.totalBytes > 0) {
            (volume.usedBytes.toFloat() / volume.totalBytes).coerceIn(0f, 1f)
        } else {
            0f
        }
        val usedLabel = stringResource(R.string.perf_storage_seg_used, Format.bytes(volume.usedBytes))
        CompositionBar(
            segments = listOf(BarSegment(fraction, colors.accent, usedLabel)),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatTile(
                label = stringResource(R.string.perf_storage_used),
                value = Format.bytes(volume.usedBytes),
            )
            StatTile(
                label = stringResource(R.string.perf_storage_free),
                value = Format.bytes(volume.freeBytes),
            )
            StatTile(
                label = stringResource(R.string.perf_storage_total),
                value = Format.bytes(volume.totalBytes),
            )
        }
    }
}
