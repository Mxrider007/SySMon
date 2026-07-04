package com.mxrider.sysmon.ui.screens.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.R
import com.mxrider.sysmon.ui.components.StatTile
import com.mxrider.sysmon.ui.theme.FluentTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GpuPanel(
    state: PerfUiState,
    modifier: Modifier = Modifier,
) {
    val colors = FluentTheme.colors
    val typography = FluentTheme.typography
    val gpu = state.gpu

    Column(modifier.fillMaxWidth()) {
        PanelHeader(title = stringResource(R.string.perf_tile_gpu))
        Spacer(Modifier.height(12.dp))

        if (gpu == null) {
            Text(
                text = stringResource(R.string.label_loading),
                style = typography.body,
                color = colors.textSecondary,
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatTile(
                    label = stringResource(R.string.perf_gpu_renderer),
                    value = gpu.renderer ?: DASH,
                )
                StatTile(
                    label = stringResource(R.string.perf_gpu_vendor),
                    value = gpu.vendor ?: DASH,
                )
                StatTile(
                    label = stringResource(R.string.perf_gpu_gl_version),
                    value = gpu.glVersion ?: DASH,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.perf_gpu_note),
            style = typography.caption,
            color = colors.textSecondary,
        )
    }
}
