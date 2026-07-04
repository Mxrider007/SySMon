package com.mxrider.sysmon.ui.screens.performance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.core.CpuSnapshot
import com.mxrider.sysmon.core.StorageSnapshot
import com.mxrider.sysmon.core.averageCurFreqKhz
import com.mxrider.sysmon.ui.theme.FluentTheme

/** Placeholder for values the platform does not expose — never fabricated. */
internal const val DASH = "—"

/** Average current frequency of the online cores, 0 when unknown. */
internal fun averageCurFreqKhz(cpu: CpuSnapshot): Long = cpu.averageCurFreqKhz()

/** Aggregate used fraction across all volumes; null when unknown. */
internal fun storageUsedFraction(snapshot: StorageSnapshot?): Float? {
    val volumes = snapshot?.volumes ?: return null
    var total = 0L
    var used = 0L
    for (volume in volumes) {
        if (volume.totalBytes > 0) {
            total += volume.totalBytes
            used += volume.usedBytes.coerceAtLeast(0L)
        }
    }
    if (total <= 0L) return null
    return (used.toDouble() / total).toFloat().coerceIn(0f, 1f)
}

/** Panel section header: 20sp subtitle on the left, optional detail on the right. */
@Composable
internal fun PanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val colors = FluentTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            style = FluentTheme.typography.subtitle,
            color = colors.textPrimary,
        )
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            Text(
                text = trailing,
                style = FluentTheme.typography.body,
                color = colors.textSecondary,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Static spec entry: secondary label column + primary value. */
@Composable
internal fun SpecRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = FluentTheme.colors
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = FluentTheme.typography.body,
            color = colors.textSecondary,
            modifier = Modifier.width(170.dp),
        )
        Text(
            text = value,
            style = FluentTheme.typography.body,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Inline caution strip (e.g. low-memory warning) — calm Fluent InfoBar. */
@Composable
internal fun InfoStrip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Warning,
) {
    val colors = FluentTheme.colors
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.layer, shape)
            .border(1.dp, colors.cardStroke, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.caution,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = FluentTheme.typography.body,
            color = colors.textPrimary,
        )
    }
}
