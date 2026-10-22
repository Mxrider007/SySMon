package com.mxrider.sysmon.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.currentState
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.action.ActionParameters
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.MainActivity
import com.mxrider.sysmon.R
import com.mxrider.sysmon.TabIntents
import com.mxrider.sysmon.core.CpuSource
import com.mxrider.sysmon.core.Format
import com.mxrider.sysmon.core.TabDestination
import com.mxrider.sysmon.data.system.DefaultCpuMonitor
import com.mxrider.sysmon.data.system.DefaultNetworkMonitor
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Home-screen widget: the four summary rows (CPU / MEM / NET / BAT) on a dark
 * card, plus an "Updated HH:mm" stamp — a widget number is only as fresh as
 * its last refresh, and saying so beats pretending to be live. Refreshes on
 * the ↻ tap and on the platform's 30-minute cycle; tapping the card opens the
 * Performance tab.
 *
 * Sampling happens right here at refresh time with a genuine one-second
 * two-sample window, so the CPU% and network rates are real instantaneous
 * readings (the same honesty rules as everywhere: ~ marks the frequency
 * estimate, a dash marks anything unreadable).
 */
class SysMonWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = sampleWidgetData(context)
        provideContent {
            // The session outlives provideGlance's first pass by ~45 s, and
            // update() only reloads state into it, so a refresh tap inside
            // that window arrives as a new nonce, not as a fresh provideGlance.
            val nonce = currentState(REFRESH_NONCE) ?: 0L
            var data by remember { mutableStateOf(initial) }
            var sampledFor by remember { mutableLongStateOf(nonce) }
            LaunchedEffect(nonce) {
                if (nonce != sampledFor) {
                    data = sampleWidgetData(context)
                    sampledFor = nonce
                }
            }
            WidgetContent(data)
        }
    }
}

private val REFRESH_NONCE = longPreferencesKey("refresh_nonce")

class SysMonWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SysMonWidget()
}

/**
 * ↻ tap: bumps the instance's refresh nonce, then updates it. A running
 * session resamples on the new nonce; otherwise update() starts a new
 * provideGlance, which samples anyway.
 */
class RefreshSysMonWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[REFRESH_NONCE] = (prefs[REFRESH_NONCE] ?: 0L) + 1
        }
        SysMonWidget().update(context, glanceId)
    }
}

private class WidgetData(
    val cpu: String,
    val mem: String,
    val net: String,
    val bat: String,
    val updatedAt: String,
)

private const val DASH = "—"

/**
 * One refresh's samples. Fresh monitor instances: the delta-based sources
 * (CPU jiffies where readable, network counters) get a real 1 s window from
 * two samples instead of a fabricated first-call zero, and nothing here can
 * slice the UI's or the service's sampling windows.
 */
private suspend fun sampleWidgetData(context: Context): WidgetData {
    val cpuMonitor = DefaultCpuMonitor()
    val netMonitor = DefaultNetworkMonitor(context)
    runCatching { cpuMonitor.sample() }
    runCatching { netMonitor.sample() }
    delay(1_000)
    val cpu = runCatching { cpuMonitor.sample() }.getOrNull()
    val net = runCatching { netMonitor.sample() }.getOrNull()
    val mem = runCatching { Graph.memory.sample() }.getOrNull()
    val bat = runCatching { Graph.battery.sample() }.getOrNull()

    val cpuText = cpu?.takeIf { it.source != CpuSource.UNAVAILABLE }?.let {
        val prefix = if (it.source == CpuSource.FREQ_ESTIMATE) "~" else ""
        prefix + Format.percent(it.utilization)
    } ?: DASH
    val memText = mem?.takeIf { it.totalBytes > 0 }?.let {
        Format.percent((it.usedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f))
    } ?: DASH
    val netText = net?.takeIf { it.totalRxBytes != 0L || it.totalTxBytes != 0L }?.let {
        "↓${Format.compactBits(it.rxBytesPerSec)} ↑${Format.compactBits(it.txBytesPerSec)}"
    } ?: DASH
    val batText = bat?.let { b ->
        buildString {
            append("${b.levelPercent}%")
            b.temperatureC?.let { append(String.format(Locale.US, " · %.0f°C", it)) }
        }
    } ?: DASH
    val stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
    return WidgetData(cpuText, memText, netText, batText, stamp)
}

private val labelStyle = TextStyle(
    color = ColorProvider(Color(0xFF9BA0A6)),
    fontSize = 11.sp,
)
// 13sp, not 14: the BAT row ("100% · 30°C") has to clear its label inside a
// 2-cell-wide card or the degree unit clips at the edge.
private val valueStyle = TextStyle(
    color = ColorProvider(Color.White),
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
)
private val stampStyle = TextStyle(
    color = ColorProvider(Color(0xFF6E7377)),
    fontSize = 10.sp,
)

@Composable
private fun WidgetContent(data: WidgetData) {
    val context = androidx.glance.LocalContext.current
    val openPerformance = Intent(context, MainActivity::class.java)
        .putExtra(TabIntents.EXTRA_OPEN_TAB, TabDestination.PERFORMANCE.name)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_bg))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(actionStartActivity(openPerformance)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WidgetRow(context.getString(R.string.overlay_label_cpu), data.cpu)
        Spacer(GlanceModifier.height(4.dp))
        WidgetRow(context.getString(R.string.overlay_label_mem), data.mem)
        Spacer(GlanceModifier.height(4.dp))
        WidgetRow(context.getString(R.string.overlay_label_net), data.net)
        Spacer(GlanceModifier.height(4.dp))
        WidgetRow(context.getString(R.string.overlay_label_bat), data.bat)
        Spacer(GlanceModifier.height(6.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.getString(R.string.widget_updated, data.updatedAt),
                style = stampStyle,
            )
            Spacer(GlanceModifier.defaultWeight())
            // The refresh glyph is its own tap target so the card tap can
            // keep opening the app.
            Text(
                text = "↻",
                style = TextStyle(color = ColorProvider(Color(0xFF9BA0A6)), fontSize = 14.sp),
                modifier = GlanceModifier
                    .padding(horizontal = 6.dp)
                    // Without this TalkBack announces the bare glyph; the
                    // string existed but had never been wired to anything.
                    .semantics { contentDescription = context.getString(R.string.widget_refresh) }
                    .clickable(actionRunCallback<RefreshSysMonWidgetAction>()),
            )
        }
    }
}

@Composable
private fun WidgetRow(label: String, value: String) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = labelStyle)
        Spacer(GlanceModifier.defaultWeight())
        Text(text = value, style = valueStyle)
    }
}
