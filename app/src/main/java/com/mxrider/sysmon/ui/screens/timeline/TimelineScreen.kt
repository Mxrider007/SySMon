package com.mxrider.sysmon.ui.screens.timeline

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.Format
import com.mxrider.sysmon.core.LogInterval
import com.mxrider.sysmon.data.logging.TimelineSeries
import com.mxrider.sysmon.service.CpuNotificationService
import com.mxrider.sysmon.ui.components.FluentCard
import com.mxrider.sysmon.ui.components.FluentDropDown
import com.mxrider.sysmon.ui.components.FluentToggle
import com.mxrider.sysmon.ui.components.TitleBar
import com.mxrider.sysmon.ui.components.chart.TimelineChart
import com.mxrider.sysmon.ui.theme.FluentMetrics
import com.mxrider.sysmon.ui.theme.FluentTheme
import java.util.Locale

/**
 * Historical timeline of the performance log — the browse side of the
 * recording that Settings → Performance logging (same DataStore keys) turns
 * on. Six stacked time charts over a selectable window, all fed by
 * [TimelineViewModel] from the on-device CSV ring; recording gaps break the
 * lines, screen-on time is shaded, and metrics the device never reported
 * stay blank rather than invented.
 */
@Composable
fun TimelineScreen(modifier: Modifier = Modifier) {
    val vm: TimelineViewModel = viewModel()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val colors = FluentTheme.colors
    val context = LocalContext.current

    val deniedMessage = stringResource(R.string.timeline_notif_denied)
    val requestLogPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.setLogging(true)
        } else {
            Toast.makeText(context, deniedMessage, Toast.LENGTH_LONG).show()
        }
    }

    val intervalLabels = mapOf(
        LogInterval.SEC_1 to stringResource(R.string.settings_log_interval_1s),
        LogInterval.SEC_5 to stringResource(R.string.settings_log_interval_5s),
        LogInterval.SEC_30 to stringResource(R.string.settings_log_interval_30s),
        LogInterval.MIN_5 to stringResource(R.string.settings_log_interval_5m),
    )
    val rangeLabels = mapOf(
        TimelineRange.HOUR_1 to stringResource(R.string.timeline_range_1h),
        TimelineRange.HOUR_6 to stringResource(R.string.timeline_range_6h),
        TimelineRange.DAY_1 to stringResource(R.string.timeline_range_24h),
        TimelineRange.DAY_7 to stringResource(R.string.timeline_range_7d),
        TimelineRange.ALL to stringResource(R.string.timeline_range_all),
    )

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(FluentMetrics.pagePadding),
    ) {
        TitleBar(
            title = stringResource(R.string.nav_timeline),
            subtitle = if (settings.logging) {
                stringResource(
                    R.string.timeline_status_recording,
                    intervalLabels.getValue(settings.logInterval),
                )
            } else {
                stringResource(R.string.timeline_status_off)
            },
        )
        Spacer(Modifier.height(16.dp))

        FluentCard(Modifier.fillMaxWidth()) {
            ControlRow(
                label = stringResource(R.string.timeline_record),
                description = stringResource(R.string.timeline_record_desc),
            ) {
                FluentToggle(
                    checked = settings.logging,
                    label = stringResource(R.string.timeline_record),
                    onCheckedChange = { checked ->
                        when {
                            !checked -> vm.setLogging(false)
                            CpuNotificationService.canPostNotifications(context) ->
                                vm.setLogging(true)
                            else -> requestLogPermission.launch(
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        }
                    },
                )
            }
            ControlRow(label = stringResource(R.string.settings_log_interval)) {
                FluentDropDown(
                    options = LogInterval.entries,
                    selected = settings.logInterval,
                    onSelect = vm::setLogInterval,
                    label = { intervalLabels.getValue(it) },
                )
            }
            ControlRow(label = stringResource(R.string.timeline_show_range)) {
                FluentDropDown(
                    options = TimelineRange.entries,
                    selected = ui.range,
                    onSelect = vm::setRange,
                    label = { rangeLabels.getValue(it) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.timeline_settings_note),
                style = FluentTheme.typography.caption,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.height(16.dp))

        val series = ui.series
        when {
            ui.loading && ui.bounds == null -> {
                Text(
                    text = stringResource(R.string.label_loading),
                    style = FluentTheme.typography.body,
                    color = colors.textSecondary,
                )
            }

            ui.bounds == null || series == null || series.times.isEmpty() -> {
                FluentCard(Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.timeline_empty_title),
                        style = FluentTheme.typography.subtitleSmall,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (settings.logging) {
                            stringResource(R.string.timeline_empty_recording)
                        } else {
                            stringResource(R.string.timeline_empty_body)
                        },
                        style = FluentTheme.typography.body,
                        color = colors.textSecondary,
                    )
                }
            }

            else -> TimelineCharts(series)
        }
    }
}

@Composable
private fun TimelineCharts(series: TimelineSeries) {
    val colors = FluentTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.timeline_legend_screen_on),
            style = FluentTheme.typography.caption,
            color = colors.textSecondary,
        )
        Text(
            text = pluralStringResource(
                R.plurals.timeline_sample_count,
                series.sampleCount,
                String.format(Locale.US, "%,d", series.sampleCount),
            ),
            style = FluentTheme.typography.caption,
            color = colors.textSecondary,
        )
    }
    Spacer(Modifier.height(12.dp))

    ChartBlock(series, stringResource(R.string.timeline_chart_cpu), series.cpuPct) { s, cd ->
        TimelineChart(
            times = s.times,
            values = s.cpuPct,
            windowStartMs = s.windowStartMs,
            windowEndMs = s.windowEndMs,
            gapThresholdMs = s.gapThresholdMs,
            bucketMillis = s.bucketMillis,
            modifier = CHART_MODIFIER,
            maxValue = 100f,
            screenOnFraction = s.screenOnFraction,
            valueFormatter = { String.format(Locale.US, "%.0f%%", it) },
            contentDescription = cd,
        )
    }

    ChartBlock(series, stringResource(R.string.timeline_chart_memory), series.memUsedBytes) { s, cd ->
        // Fixed 0..total when the log reported total RAM; autoscale otherwise.
        val total = s.memTotalBytes.toFloat()
        TimelineChart(
            times = s.times,
            values = s.memUsedBytes,
            windowStartMs = s.windowStartMs,
            windowEndMs = s.windowEndMs,
            gapThresholdMs = s.gapThresholdMs,
            bucketMillis = s.bucketMillis,
            modifier = CHART_MODIFIER,
            maxValue = if (total > 0f) total else 1f,
            autoScale = total <= 0f,
            minAutoScale = 1024f * 1024f,
            screenOnFraction = s.screenOnFraction,
            valueFormatter = { Format.bytes(it.toLong()) },
            ceilingFormatter = { Format.bytes(it.toLong()) },
            contentDescription = cd,
        )
    }

    ChartBlock(series, stringResource(R.string.timeline_chart_battery), series.batteryPct) { s, cd ->
        TimelineChart(
            times = s.times,
            values = s.batteryPct,
            windowStartMs = s.windowStartMs,
            windowEndMs = s.windowEndMs,
            gapThresholdMs = s.gapThresholdMs,
            bucketMillis = s.bucketMillis,
            modifier = CHART_MODIFIER,
            maxValue = 100f,
            screenOnFraction = s.screenOnFraction,
            valueFormatter = { String.format(Locale.US, "%.0f%%", it) },
            contentDescription = cd,
        )
    }

    ChartBlock(series, stringResource(R.string.timeline_chart_power), series.powerW) { s, cd ->
        // Magnitude, like the Battery panel's chart: the level series above
        // carries the charge/discharge direction.
        TimelineChart(
            times = s.times,
            values = s.powerW,
            windowStartMs = s.windowStartMs,
            windowEndMs = s.windowEndMs,
            gapThresholdMs = s.gapThresholdMs,
            bucketMillis = s.bucketMillis,
            modifier = CHART_MODIFIER,
            autoScale = true,
            minAutoScale = 1f,
            screenOnFraction = s.screenOnFraction,
            valueFormatter = { Format.watts(it.toDouble()) },
            ceilingFormatter = { Format.watts(it.toDouble()) },
            contentDescription = cd,
        )
    }

    ChartBlock(series, stringResource(R.string.timeline_chart_temp), series.batteryTempC) { s, cd ->
        TimelineChart(
            times = s.times,
            values = s.batteryTempC,
            windowStartMs = s.windowStartMs,
            windowEndMs = s.windowEndMs,
            gapThresholdMs = s.gapThresholdMs,
            bucketMillis = s.bucketMillis,
            modifier = CHART_MODIFIER,
            maxValue = 50f,
            screenOnFraction = s.screenOnFraction,
            valueFormatter = { Format.celsius(it.toDouble()) },
            contentDescription = cd,
        )
    }

    ChartBlock(series, stringResource(R.string.timeline_chart_network), series.netRxBytesPerSec) { s, cd ->
        TimelineChart(
            times = s.times,
            values = s.netRxBytesPerSec,
            windowStartMs = s.windowStartMs,
            windowEndMs = s.windowEndMs,
            gapThresholdMs = s.gapThresholdMs,
            bucketMillis = s.bucketMillis,
            modifier = CHART_MODIFIER,
            autoScale = true,
            minAutoScale = 1024f,
            secondaryValues = s.netTxBytesPerSec,
            screenOnFraction = s.screenOnFraction,
            primaryLabel = stringResource(R.string.timeline_net_receive),
            secondaryLabel = stringResource(R.string.timeline_net_send),
            valueFormatter = { Format.bitsPerSec(it.toDouble()) },
            ceilingFormatter = { Format.bitsPerSec(it.toDouble()) },
            contentDescription = cd,
        )
    }
}

/**
 * Section label + chart, skipped entirely (no empty frame) when the metric
 * was never reported inside the window — the GPU-panel rule: omit, don't
 * draw an all-NaN chart.
 */
@Composable
private fun ChartBlock(
    series: TimelineSeries,
    label: String,
    values: FloatArray,
    content: @Composable (TimelineSeries, String) -> Unit,
) {
    if (!values.any { it.isFinite() }) return
    Text(
        text = label,
        style = FluentTheme.typography.bodyStrong,
        color = FluentTheme.colors.textPrimary,
    )
    Spacer(Modifier.height(6.dp))
    content(series, stringResource(R.string.timeline_cd_chart, label))
    Spacer(Modifier.height(20.dp))
}

/** Shared plot frame; Modifier chains are immutable, so one instance is safe. */
private val CHART_MODIFIER: Modifier = Modifier
    .fillMaxWidth()
    .height(160.dp)

/** Settings-style label + control row (local copy of SettingsScreen's private row). */
@Composable
private fun ControlRow(
    label: String,
    description: String? = null,
    control: @Composable () -> Unit,
) {
    val colors = FluentTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = FluentTheme.typography.body,
                color = colors.textPrimary,
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = FluentTheme.typography.caption,
                    color = colors.textSecondary,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        control()
    }
}
