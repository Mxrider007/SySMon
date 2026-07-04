package com.mxrider.sysmon.ui.components.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.core.ChartHistory
import com.mxrider.sysmon.ui.theme.FluentTheme
import kotlin.math.max

/**
 * Performance sidebar entry: 60x32dp sparkline (fill + stroke, no grid or
 * labels) next to a title and live value line. The whole tile is clickable;
 * selection shows the Win11 accent pill on the left edge.
 *
 * The sparkline auto-scales to the nice ceiling of the window max, never below
 * 1, so 0..1 fraction series keep a fixed full-scale of 1.
 */
@Composable
fun MiniChartTile(
    title: String,
    value: String,
    history: ChartHistory,
    windowPoints: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryValue: String? = null,
) {
    val colors = FluentTheme.colors
    val typography = FluentTheme.typography
    val linePath = remember { Path() }
    val fillPath = remember { Path() }

    Row(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) colors.selectionFill else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Selection pill at the very left edge (3x16dp, accentControl).
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .background(
                    if (selected) colors.accentControl else Color.Transparent,
                    RoundedCornerShape(1.5.dp),
                ),
        )
        Spacer(Modifier.width(9.dp))
        Canvas(modifier = Modifier.size(width = 60.dp, height = 32.dp)) {
            // State read subscribes this draw scope to sample pushes.
            history.version.value
            val values = history.window(windowPoints)
            var windowMax = 0f
            for (v in values) if (v.isFinite() && v > windowMax) windowMax = v
            val effectiveMax = max(1f, niceCeil(windowMax))
            val hairline = 1.dp.toPx()

            buildSeriesPaths(values, effectiveMax, size.width, size.height, linePath, fillPath)
            drawPath(fillPath, colors.chartFill)
            drawPath(linePath, colors.accent, style = Stroke(width = hairline))
            drawRect(
                color = colors.chartBorder,
                topLeft = Offset(hairline / 2f, hairline / 2f),
                size = Size(size.width - hairline, size.height - hairline),
                style = Stroke(width = hairline),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(
                text = title,
                style = typography.bodyStrong,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = typography.caption,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondaryValue != null) {
                Text(
                    text = secondaryValue,
                    style = typography.caption,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
