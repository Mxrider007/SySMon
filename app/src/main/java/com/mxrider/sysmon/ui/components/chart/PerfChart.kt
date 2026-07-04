package com.mxrider.sysmon.ui.components.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.core.ChartHistory
import com.mxrider.sysmon.ui.theme.FluentTheme
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Auto-scale never drops below this ceiling input (1 KiB/s), per contract. */
private const val MIN_AUTO_SCALE = 1024f

/**
 * The Windows 11 Task Manager area chart. Caller sets the height (typically
 * 260dp) via [modifier]; label rows sit outside the plot.
 */
@Composable
fun PerfChart(
    history: ChartHistory,
    windowPoints: Int,
    modifier: Modifier = Modifier,
    maxValue: Float = 1f,
    autoScale: Boolean = false,
    secondaryHistory: ChartHistory? = null,
    topLeftLabel: String? = null,
    topRightLabel: String? = null,
    bottomLeftLabel: String? = null,
    bottomRightLabel: String? = null,
    primaryLabel: String? = null,
    secondaryLabel: String? = null,
    valueFormatter: (Float) -> String = { it.toString() },
    contentDescription: String? = null,
) {
    val colors = FluentTheme.colors
    val typography = FluentTheme.typography
    val textMeasurer = rememberTextMeasurer()

    val captionSecondary = remember(typography, colors.textSecondary) {
        typography.caption.copy(color = colors.textSecondary)
    }
    val tooltipStyle = remember(typography, colors.textPrimary) {
        typography.caption.copy(color = colors.textPrimary)
    }
    val showDirectLabels = secondaryHistory != null
    val primaryLabelLayout = remember(showDirectLabels, primaryLabel, captionSecondary) {
        if (showDirectLabels && primaryLabel != null) {
            textMeasurer.measure(primaryLabel, captionSecondary)
        } else null
    }
    val secondaryLabelLayout = remember(showDirectLabels, secondaryLabel, captionSecondary) {
        if (showDirectLabels && secondaryLabel != null) {
            textMeasurer.measure(secondaryLabel, captionSecondary)
        } else null
    }

    // 4dp on/off per contract; raw pixels would render near-solid on xxhdpi,
    // erasing the only non-color cue distinguishing the secondary series.
    val density = LocalDensity.current
    val dashEffect = remember(density) {
        with(density) { PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) }
    }
    // Keyed on the data sources: a swapped-in history restarts its version
    // sequence, which would otherwise read as a fresh cache hit.
    val geometry = remember(history, secondaryHistory) { ChartGeometryCache() }
    // X position of an active press/drag inside the plot; NaN = no touch.
    val touchX = remember { mutableFloatStateOf(Float.NaN) }

    Column(modifier = modifier) {
        if (topLeftLabel != null || topRightLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(topLeftLabel ?: "", style = typography.caption, color = colors.textSecondary)
                Text(topRightLabel ?: "", style = typography.caption, color = colors.textSecondary)
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics { this.contentDescription = contentDescription ?: "" }
                .pointerInput(Unit) {
                    // Single gesture owner for press-hold and drag. The old
                    // split (detectTapGestures + detectDragGestures) handed the
                    // crosshair between detectors on drag start, blanking it
                    // for one frame.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        touchX.floatValue = down.position.x
                        val slop = viewConfiguration.touchSlop
                        var dragging = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break
                                if (!change.pressed) break
                                // The page's vertical scroll consumed the
                                // gesture before we claimed it: cede, exactly
                                // like the old drag detector's cancel path.
                                if (!dragging && change.isConsumed) break
                                if (!dragging &&
                                    (change.position - down.position).getDistance() > slop
                                ) {
                                    dragging = true
                                }
                                if (dragging) change.consume()
                                touchX.floatValue = change.position.x
                            }
                        } finally {
                            // Up, cede and cancellation alike: no stale crosshair.
                            touchX.floatValue = Float.NaN
                        }
                    }
                },
        ) {
            // State reads inside the draw scope subscribe it to sample pushes.
            val version = history.version.value
            val secondaryVersion = secondaryHistory?.version?.value ?: Long.MIN_VALUE

            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            // Values, scale and series paths rebuild only when data or layout
            // changed; crosshair-only frames (input-rate redraws during a
            // drag, up to 120 Hz) reuse the cached geometry as-is.
            geometry.refresh(
                history = history,
                secondaryHistory = secondaryHistory,
                version = version,
                secondaryVersion = secondaryVersion,
                windowPoints = windowPoints,
                maxValue = maxValue,
                autoScale = autoScale,
                width = w,
                height = h,
            )
            val values = geometry.values
            val secondaryValues = geometry.secondaryValues
            val effectiveMax = geometry.effectiveMax
            val n = values.size
            val hairline = 1.dp.toPx()
            val seriesStroke = 1.5.dp.toPx()
            val stepX = w / (n - 1).coerceAtLeast(1)

            // Horizontal gridlines at 25/50/75% of the plot height.
            for (i in 1..3) {
                val y = h * i / 4f
                drawLine(colors.chartGrid, Offset(0f, y), Offset(w, y), hairline)
            }

            // Vertical gridlines scroll leftward with the data, like Win11.
            val samplesPerGridline = (windowPoints / 6).coerceAtLeast(1)
            val spacing = w / 6f
            val phasePx = (version % samplesPerGridline).toFloat() / samplesPerGridline * spacing
            var gx = w - phasePx
            while (gx > hairline) {
                if (gx < w - hairline) {
                    drawLine(colors.chartGrid, Offset(gx, 0f), Offset(gx, h), hairline)
                }
                gx -= spacing
            }

            // Series: primary area + stroke, secondary dashed stroke.
            clipRect(0f, 0f, w, h) {
                drawPath(geometry.fillPath, colors.chartFill)
                drawPath(
                    geometry.linePath,
                    colors.accent,
                    style = Stroke(width = seriesStroke, join = StrokeJoin.Round),
                )
                if (secondaryValues != null) {
                    drawPath(
                        geometry.secondaryPath,
                        colors.accent,
                        style = Stroke(
                            width = seriesStroke,
                            join = StrokeJoin.Round,
                            pathEffect = dashEffect,
                        ),
                    )
                }
            }

            // 1dp border around the plot, inset so it is fully inside.
            drawRect(
                color = colors.chartBorder,
                topLeft = Offset(hairline / 2f, hairline / 2f),
                size = Size(w - hairline, h - hairline),
                style = Stroke(width = hairline),
            )

            // Direct series labels (identity by line style + text, never color alone).
            if (primaryLabelLayout != null || secondaryLabelLayout != null) {
                val swatchLen = 12.dp.toPx()
                val swatchStroke = 2.dp.toPx()
                val startX = 8.dp.toPx()
                val textX = startX + swatchLen + 6.dp.toPx()
                var rowTop = 6.dp.toPx()
                if (primaryLabelLayout != null) {
                    val cy = rowTop + primaryLabelLayout.size.height / 2f
                    drawLine(colors.accent, Offset(startX, cy), Offset(startX + swatchLen, cy), swatchStroke)
                    drawText(primaryLabelLayout, topLeft = Offset(textX, rowTop))
                    rowTop += primaryLabelLayout.size.height + 2.dp.toPx()
                }
                if (secondaryLabelLayout != null) {
                    val cy = rowTop + secondaryLabelLayout.size.height / 2f
                    drawLine(
                        colors.accent,
                        Offset(startX, cy),
                        Offset(startX + swatchLen, cy),
                        swatchStroke,
                        pathEffect = dashEffect,
                    )
                    drawText(secondaryLabelLayout, topLeft = Offset(textX, rowTop))
                }
            }

            // Crosshair + tooltip while pressed/dragging.
            val tx = touchX.floatValue
            if (!tx.isNaN() && n > 0) {
                var idx = (tx / stepX).roundToInt().coerceIn(0, n - 1)
                if (!values[idx].isFinite()) {
                    var found = -1
                    for (d in 1 until n) {
                        val lo = idx - d
                        val hi = idx + d
                        if (lo >= 0 && values[lo].isFinite()) {
                            found = lo
                            break
                        }
                        if (hi < n && values[hi].isFinite()) {
                            found = hi
                            break
                        }
                    }
                    idx = found
                }
                if (idx >= 0) {
                    val v = values[idx]
                    val px = idx * stepX
                    val py = h * (1f - (v / effectiveMax).coerceIn(0f, 1f))
                    drawLine(colors.textTertiary, Offset(px, 0f), Offset(px, h), hairline)
                    drawCircle(colors.accent, radius = 3.dp.toPx(), center = Offset(px, py))

                    val tooltipLayout = textMeasurer.measure(valueFormatter(v), tooltipStyle)
                    val pad = 8.dp.toPx()
                    val gap = 12.dp.toPx()
                    val boxW = tooltipLayout.size.width + pad * 2f
                    val boxH = tooltipLayout.size.height + pad * 2f
                    var boxX = px + gap
                    if (boxX + boxW > w) boxX = px - gap - boxW
                    boxX = boxX.coerceIn(0f, max(0f, w - boxW))
                    var boxY = py - boxH - gap
                    if (boxY < 0f) boxY = py + gap
                    boxY = boxY.coerceIn(0f, max(0f, h - boxH))
                    val corner = CornerRadius(4.dp.toPx())
                    drawRoundRect(
                        colors.card,
                        topLeft = Offset(boxX, boxY),
                        size = Size(boxW, boxH),
                        cornerRadius = corner,
                    )
                    drawRoundRect(
                        colors.cardStroke,
                        topLeft = Offset(boxX, boxY),
                        size = Size(boxW, boxH),
                        cornerRadius = corner,
                        style = Stroke(width = hairline),
                    )
                    drawText(tooltipLayout, topLeft = Offset(boxX + pad, boxY + pad))
                }
            }
        }

        if (bottomLeftLabel != null || bottomRightLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(bottomLeftLabel ?: "", style = typography.caption, color = colors.textSecondary)
                Text(bottomRightLabel ?: "", style = typography.caption, color = colors.textSecondary)
            }
        }
    }
}

/**
 * Draw-phase geometry cache for one [PerfChart]. Series values, the effective
 * scale and the built [Path]s are invalidated by data pushes ([version] /
 * [secondaryVersion]) and layout changes, never by crosshair movement.
 */
private class ChartGeometryCache {
    private var version = Long.MIN_VALUE
    private var secondaryVersion = Long.MIN_VALUE
    private var windowPoints = 0
    private var maxValue = 0f
    private var autoScale = false
    private var width = 0f
    private var height = 0f

    var values = FloatArray(0)
        private set
    var secondaryValues: FloatArray? = null
        private set
    var effectiveMax = 1f
        private set
    val linePath = Path()
    val fillPath = Path()
    val secondaryPath = Path()

    fun refresh(
        history: ChartHistory,
        secondaryHistory: ChartHistory?,
        version: Long,
        secondaryVersion: Long,
        windowPoints: Int,
        maxValue: Float,
        autoScale: Boolean,
        width: Float,
        height: Float,
    ) {
        if (version == this.version &&
            secondaryVersion == this.secondaryVersion &&
            windowPoints == this.windowPoints &&
            maxValue == this.maxValue &&
            autoScale == this.autoScale &&
            width == this.width &&
            height == this.height
        ) {
            return
        }
        this.version = version
        this.secondaryVersion = secondaryVersion
        this.windowPoints = windowPoints
        this.maxValue = maxValue
        this.autoScale = autoScale
        this.width = width
        this.height = height

        values = history.window(windowPoints)
        val secondary = secondaryHistory?.window(windowPoints)
        secondaryValues = secondary

        var scale = if (autoScale) {
            var windowMax = 0f
            for (v in values) if (v.isFinite() && v > windowMax) windowMax = v
            if (secondary != null) {
                for (v in secondary) if (v.isFinite() && v > windowMax) windowMax = v
            }
            niceCeil(max(windowMax, MIN_AUTO_SCALE))
        } else {
            maxValue
        }
        if (!(scale > 0f) || !scale.isFinite()) scale = 1f
        effectiveMax = scale

        buildSeriesPaths(values, effectiveMax, width, height, linePath, fillPath)
        if (secondary != null) {
            buildSeriesPaths(secondary, effectiveMax, width, height, secondaryPath, null)
        } else {
            secondaryPath.rewind()
        }
    }
}

/** Smallest value of the form {1, 2, 5} * 10^n that is >= [x]. */
internal fun niceCeil(x: Float): Float {
    if (x.isNaN() || !x.isFinite() || x <= 0f) return 1f
    val exponent = floor(log10(x.toDouble()))
    val base = 10.0.pow(exponent)
    val fraction = x / base
    val nice = when {
        fraction <= 1.0 -> 1.0
        fraction <= 2.0 -> 2.0
        fraction <= 5.0 -> 5.0
        else -> 10.0
    }
    return (nice * base).toFloat()
}

/**
 * Rebuilds [linePath] (and, when non-null, [fillPath] closed to the baseline)
 * from [values] mapped oldest→newest, left→right. NaN samples break the path
 * into separate subpaths (gaps). Paths are rewound, not reallocated.
 */
internal fun buildSeriesPaths(
    values: FloatArray,
    effectiveMax: Float,
    width: Float,
    height: Float,
    linePath: Path,
    fillPath: Path?,
) {
    linePath.rewind()
    fillPath?.rewind()
    val n = values.size
    if (n == 0) return
    val stepX = width / (n - 1).coerceAtLeast(1)
    var started = false
    var prevX = 0f
    for (i in 0 until n) {
        val v = values[i]
        if (v.isFinite()) {
            val x = i * stepX
            val y = height * (1f - (v / effectiveMax).coerceIn(0f, 1f))
            if (!started) {
                linePath.moveTo(x, y)
                fillPath?.moveTo(x, height)
                fillPath?.lineTo(x, y)
                started = true
            } else {
                linePath.lineTo(x, y)
                fillPath?.lineTo(x, y)
            }
            prevX = x
        } else if (started) {
            fillPath?.lineTo(prevX, height)
            fillPath?.close()
            started = false
        }
    }
    if (started && fillPath != null) {
        fillPath.lineTo(prevX, height)
        fillPath.close()
    }
}
