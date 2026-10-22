package com.mxrider.sysmon.ui.components.chart

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.ui.theme.FluentTheme
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Time-axis sibling of [PerfChart] for the Timeline screen. X maps epoch
 * millis inside [windowStartMs, windowEndMs] instead of a sample index, so a
 * non-uniform log cadence renders truthfully. A break in the line means
 * "nothing was recorded here" — NaN, or a time step above [gapThresholdMs] —
 * never interpolation across a gap. Vertical gridlines snap to round LOCAL
 * wall-clock boundaries with a small time caption at each; optional
 * per-bucket shading marks screen-on time. Crosshair scrubbing keeps
 * [PerfChart]'s horizontally-dominant-drag rule so page scroll survives.
 *
 * [times]/[values] (and the optional parallel arrays) are treated as
 * immutable — the geometry cache invalidates on array identity, not content.
 */
@Composable
fun TimelineChart(
    times: LongArray,
    values: FloatArray,
    windowStartMs: Long,
    windowEndMs: Long,
    gapThresholdMs: Long,
    bucketMillis: Long,
    modifier: Modifier = Modifier,
    maxValue: Float = 1f,
    autoScale: Boolean = false,
    minAutoScale: Float = 1f,
    secondaryValues: FloatArray? = null,
    screenOnFraction: FloatArray? = null,
    primaryLabel: String? = null,
    secondaryLabel: String? = null,
    valueFormatter: (Float) -> String = { it.toString() },
    ceilingFormatter: ((Float) -> String)? = null,
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
    val showDirectLabels = secondaryValues != null
    val primaryLabelLayout = remember(showDirectLabels, primaryLabel, captionSecondary, textMeasurer) {
        if (showDirectLabels && primaryLabel != null) {
            textMeasurer.measure(primaryLabel, captionSecondary)
        } else null
    }
    val secondaryLabelLayout = remember(showDirectLabels, secondaryLabel, captionSecondary, textMeasurer) {
        if (showDirectLabels && secondaryLabel != null) {
            textMeasurer.measure(secondaryLabel, captionSecondary)
        } else null
    }

    val density = LocalDensity.current
    val dashEffect = remember(density) {
        with(density) { PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) }
    }
    // Keyed on the measurer/style like PerfChart's caches: a density or theme
    // change replaces cached layouts that were measured under the old style.
    val geometry = remember(textMeasurer, captionSecondary) { TimelineGeometryCache() }
    val touchX = remember { mutableFloatStateOf(Float.NaN) }
    val ceilingCache = remember(textMeasurer, captionSecondary) { CeilingLabelCache2() }
    // Month names and day/month order follow the app's language. The zone is
    // resolved when formatting, so a time-zone change while the process
    // lives (the logging service keeps it alive for days) is picked up.
    val locale = LocalConfiguration.current.locales[0]
    val formats = remember(locale) { TimelineFormats(locale) }

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics { this.contentDescription = contentDescription ?: "" }
                .pointerInput(Unit) {
                    // Same single gesture owner + cede rules as PerfChart: the
                    // page scroll must keep working over a page that is mostly
                    // charts.
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
                                if (!dragging && change.isConsumed) break
                                if (!dragging) {
                                    val delta = change.position - down.position
                                    if (delta.getDistance() > slop) {
                                        if (abs(delta.x) >= abs(delta.y)) {
                                            dragging = true
                                        } else {
                                            break
                                        }
                                    }
                                }
                                if (dragging) change.consume()
                                touchX.floatValue = change.position.x
                            }
                        } finally {
                            touchX.floatValue = Float.NaN
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f || windowEndMs <= windowStartMs) return@Canvas

            geometry.refresh(
                times = times,
                values = values,
                secondaryValues = secondaryValues,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
                gapThresholdMs = gapThresholdMs,
                maxValue = maxValue,
                autoScale = autoScale,
                minAutoScale = minAutoScale,
                width = w,
                height = h,
                lonePointTickPx = 2.dp.toPx(),
                measurer = textMeasurer,
                labelStyle = captionSecondary,
                formats = formats,
            )
            val effectiveMax = geometry.effectiveMax
            val hairline = 1.dp.toPx()
            val seriesStroke = 1.5.dp.toPx()
            val spanMs = (windowEndMs - windowStartMs).toFloat()

            // Screen-on shading first, underneath everything. Alpha follows
            // the recorded on-fraction of each bucket, so a bucket that was
            // half awake honestly reads half as bright.
            if (screenOnFraction != null) {
                val bucketPx = max(1f, bucketMillis / spanMs * w)
                for (i in times.indices) {
                    val frac = screenOnFraction.getOrNull(i) ?: 0f
                    if (frac <= 0f) continue
                    val cx = (times[i] - windowStartMs) / spanMs * w
                    drawRect(
                        color = colors.textSecondary,
                        topLeft = Offset(cx - bucketPx / 2f, 0f),
                        size = Size(bucketPx, h),
                        alpha = 0.10f * frac,
                    )
                }
            }

            for (i in 1..3) {
                val y = h * i / 4f
                drawLine(colors.chartGrid, Offset(0f, y), Offset(w, y), hairline)
            }

            // Wall-clock gridlines with their time captions.
            val labelInset = 4.dp.toPx()
            var lastLabelEnd = Float.NEGATIVE_INFINITY
            for (g in geometry.gridlines) {
                if (g.x > hairline && g.x < w - hairline) {
                    drawLine(colors.chartGrid, Offset(g.x, 0f), Offset(g.x, h), hairline)
                }
                val lx = g.x + labelInset
                if (lx > lastLabelEnd + labelInset && lx + g.label.size.width < w - labelInset) {
                    drawText(g.label, topLeft = Offset(lx, h - g.label.size.height - labelInset))
                    lastLabelEnd = lx + g.label.size.width
                }
            }

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

            drawRect(
                color = colors.chartBorder,
                topLeft = Offset(hairline / 2f, hairline / 2f),
                size = Size(w - hairline, h - hairline),
                style = Stroke(width = hairline),
            )

            if (ceilingFormatter != null) {
                val layout = ceilingCache.layoutFor(
                    ceilingFormatter(effectiveMax), textMeasurer, captionSecondary,
                )
                val inset = 6.dp.toPx()
                drawText(
                    layout,
                    topLeft = Offset(max(0f, w - layout.size.width - inset), inset),
                )
            }

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

            // Crosshair: nearest recorded point by TIME (not index — points
            // are non-uniform), both series scrubbed at once like PerfChart.
            val tx = touchX.floatValue
            val n = times.size
            if (!tx.isNaN() && n > 0) {
                val targetT = windowStartMs + (tx / w * spanMs).toLong()
                var idx = nearestIndex(times, targetT)
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
                    val px = (times[idx] - windowStartMs) / spanMs * w
                    val py = h * (1f - (v / effectiveMax).coerceIn(0f, 1f))
                    drawLine(colors.textTertiary, Offset(px, 0f), Offset(px, h), hairline)
                    drawCircle(colors.accent, radius = 3.dp.toPx(), center = Offset(px, py))

                    val secondaryAt = secondaryValues?.getOrNull(idx)?.takeIf { it.isFinite() }
                    if (secondaryAt != null) {
                        val sy = h * (1f - (secondaryAt / effectiveMax).coerceIn(0f, 1f))
                        drawCircle(colors.accent, radius = 3.dp.toPx(), center = Offset(px, sy))
                    }
                    val timeLine = geometry.tooltipTimeFormat.format(
                        Instant.ofEpochMilli(times[idx]).atZone(ZoneId.systemDefault()),
                    )
                    val tooltipText = if (secondaryAt != null) {
                        val first = primaryLabel?.let { "$it: " }.orEmpty() + valueFormatter(v)
                        val second =
                            secondaryLabel?.let { "$it: " }.orEmpty() + valueFormatter(secondaryAt)
                        "$timeLine\n$first\n$second"
                    } else {
                        "$timeLine\n${valueFormatter(v)}"
                    }
                    val tooltipLayout = textMeasurer.measure(tooltipText, tooltipStyle)
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

        // Window edges under the plot (recomposes with the window parameters —
        // never draw-phase cache state, which composition cannot observe);
        // gridline captions inside the plot cover the middle. Pinned LTR: the
        // canvas keeps time flowing left→right in RTL locales (time-series
        // convention, and Canvas coordinates don't mirror anyway), so letting
        // this Row mirror would caption the left edge with the END stamp.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formats.stampMinutes.format(
                        Instant.ofEpochMilli(windowStartMs).atZone(ZoneId.systemDefault()),
                    ),
                    style = typography.caption,
                    color = colors.textSecondary,
                )
                Text(
                    text = formats.stampMinutes.format(
                        Instant.ofEpochMilli(windowEndMs).atZone(ZoneId.systemDefault()),
                    ),
                    style = typography.caption,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** Nearest index in ascending [times] to [target] (binary search + neighbor). */
private fun nearestIndex(times: LongArray, target: Long): Int {
    var lo = 0
    var hi = times.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (times[mid] < target) lo = mid + 1 else hi = mid
    }
    if (lo > 0 && target - times[lo - 1] < times[lo] - target) lo--
    return lo
}

private class Gridline(val x: Float, val timeMs: Long, val label: TextLayoutResult)

/**
 * Draw-phase geometry for one [TimelineChart]: series paths, scale, and the
 * wall-clock gridlines with their measured captions. Invalidated by data
 * (array identity), window, scale inputs or layout size — never by crosshair
 * movement, which redraws at input rate during a scrub.
 */
private class TimelineGeometryCache {
    private var times: LongArray? = null
    private var values: FloatArray? = null
    private var secondaryValues: FloatArray? = null
    private var windowStartMs = 0L
    private var windowEndMs = 0L
    private var gapThresholdMs = 0L
    private var maxValue = 0f
    private var autoScale = false
    private var minAutoScale = 0f
    private var width = 0f
    private var height = 0f
    private var lonePointTickPx = 0f
    private var formats: TimelineFormats? = null
    private var zone: ZoneId = ZoneId.systemDefault()

    var effectiveMax = 1f
        private set
    val linePath = Path()
    val fillPath = Path()
    val secondaryPath = Path()
    var gridlines: List<Gridline> = emptyList()
        private set

    /** Tooltip stamp: seconds only when the window is fine enough to need them. */
    var tooltipTimeFormat: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        private set

    fun refresh(
        times: LongArray,
        values: FloatArray,
        secondaryValues: FloatArray?,
        windowStartMs: Long,
        windowEndMs: Long,
        gapThresholdMs: Long,
        maxValue: Float,
        autoScale: Boolean,
        minAutoScale: Float,
        width: Float,
        height: Float,
        lonePointTickPx: Float,
        measurer: TextMeasurer,
        labelStyle: TextStyle,
        formats: TimelineFormats,
    ) {
        val zone = ZoneId.systemDefault()
        if (times === this.times &&
            values === this.values &&
            secondaryValues === this.secondaryValues &&
            windowStartMs == this.windowStartMs &&
            windowEndMs == this.windowEndMs &&
            gapThresholdMs == this.gapThresholdMs &&
            maxValue == this.maxValue &&
            autoScale == this.autoScale &&
            minAutoScale == this.minAutoScale &&
            width == this.width &&
            height == this.height &&
            lonePointTickPx == this.lonePointTickPx &&
            formats === this.formats &&
            zone == this.zone
        ) {
            return
        }
        this.formats = formats
        this.zone = zone
        this.times = times
        this.values = values
        this.secondaryValues = secondaryValues
        this.windowStartMs = windowStartMs
        this.windowEndMs = windowEndMs
        this.gapThresholdMs = gapThresholdMs
        this.maxValue = maxValue
        this.autoScale = autoScale
        this.minAutoScale = minAutoScale
        this.width = width
        this.height = height
        this.lonePointTickPx = lonePointTickPx

        var scale = if (autoScale) {
            var windowMax = 0f
            for (v in values) if (v.isFinite() && v > windowMax) windowMax = v
            if (secondaryValues != null) {
                for (v in secondaryValues) if (v.isFinite() && v > windowMax) windowMax = v
            }
            niceCeil(max(windowMax, minAutoScale))
        } else {
            maxValue
        }
        if (!(scale > 0f) || !scale.isFinite()) scale = 1f
        effectiveMax = scale

        buildTimePaths(times, values, linePath, fillPath)
        if (secondaryValues != null) {
            buildTimePaths(times, secondaryValues, secondaryPath, null)
        } else {
            secondaryPath.rewind()
        }

        val spanMs = windowEndMs - windowStartMs
        tooltipTimeFormat =
            if (spanMs <= 2 * 60 * 60 * 1000L) formats.stampSeconds else formats.stampMinutes
        buildGridlines(measurer, labelStyle, formats)
    }

    private fun buildTimePaths(
        times: LongArray,
        values: FloatArray,
        linePath: Path,
        fillPath: Path?,
    ) {
        linePath.rewind()
        fillPath?.rewind()
        val n = times.size
        if (n == 0) return
        val spanMs = (windowEndMs - windowStartMs).toFloat()
        var started = false
        var prevX = 0f
        var prevT = Long.MIN_VALUE
        var subpathPoints = 0
        var subStartX = 0f
        var subStartY = 0f

        // A lone sample between gaps produces a subpath with no line segment
        // and would render as nothing — recorded data must never be
        // invisible, so it gets a short horizontal tick instead.
        fun closeSubpath() {
            fillPath?.lineTo(prevX, height)
            fillPath?.close()
            if (subpathPoints == 1 && lonePointTickPx > 0f) {
                linePath.moveTo(subStartX - lonePointTickPx, subStartY)
                linePath.lineTo(subStartX + lonePointTickPx, subStartY)
            }
            started = false
            subpathPoints = 0
        }

        for (i in 0 until n) {
            val v = values[i]
            val t = times[i]
            val isGap = started && gapThresholdMs > 0 && t - prevT > gapThresholdMs
            if (!v.isFinite() || isGap) {
                if (started) closeSubpath()
                if (!v.isFinite()) {
                    prevT = t
                    continue
                }
            }
            val x = (t - windowStartMs) / spanMs * width
            val y = height * (1f - (v / effectiveMax).coerceIn(0f, 1f))
            if (!started) {
                linePath.moveTo(x, y)
                fillPath?.moveTo(x, height)
                fillPath?.lineTo(x, y)
                started = true
                subpathPoints = 1
                subStartX = x
                subStartY = y
            } else {
                linePath.lineTo(x, y)
                fillPath?.lineTo(x, y)
                subpathPoints++
            }
            prevX = x
            prevT = t
        }
        if (started) closeSubpath()
    }

    /**
     * Gridlines at round LOCAL wall-clock boundaries: the step is the
     * smallest candidate giving at most [MAX_GRIDLINES] lines, anchored by
     * truncating to the day (or hour) in the default zone, so a "3 h" step
     * lands on 00:00 / 03:00 / 06:00 rather than arbitrary offsets.
     */
    private fun buildGridlines(measurer: TextMeasurer, style: TextStyle, formats: TimelineFormats) {
        val spanMs = windowEndMs - windowStartMs
        // Beyond the candidate list (multi-month "everything" spans), fall
        // back to a whole-day step sized to the span instead of truncating
        // the gridline run at MAX_GRIDLINES weeks.
        val stepMs = GRID_STEPS_MS.firstOrNull { spanMs / it <= MAX_GRIDLINES }
            ?: (DAY_MS * ((spanMs / MAX_GRIDLINES + DAY_MS - 1) / DAY_MS))
        val dayStep = stepMs >= DAY_MS
        val labelFormat = if (dayStep) formats.gridDay else formats.gridTime

        val start = Instant.ofEpochMilli(windowStartMs).atZone(zone)
        var tick = if (dayStep) {
            start.truncatedTo(ChronoUnit.DAYS)
        } else {
            // Anchor sub-day steps at the last day boundary so multiples of
            // the step stay aligned with midnight in local time.
            var t = start.truncatedTo(ChronoUnit.DAYS)
            while (t.plusNanos(stepMs * 1_000_000).toInstant().toEpochMilli() <= windowStartMs) {
                t = t.plusNanos(stepMs * 1_000_000)
            }
            t
        }
        while (tick.toInstant().toEpochMilli() < windowStartMs) {
            tick = advance(tick, stepMs, dayStep)
        }

        val out = ArrayList<Gridline>()
        val spanF = spanMs.toFloat()
        var guard = 0
        while (guard++ < MAX_GRIDLINES * 2) {
            val ms = tick.toInstant().toEpochMilli()
            if (ms > windowEndMs) break
            val x = (ms - windowStartMs) / spanF * width
            out.add(Gridline(x, ms, measurer.measure(labelFormat.format(tick), style)))
            tick = advance(tick, stepMs, dayStep)
        }
        gridlines = out
    }

    /** Day steps advance calendar days (DST-safe); sub-day steps add wall time. */
    private fun advance(tick: ZonedDateTime, stepMs: Long, dayStep: Boolean): ZonedDateTime =
        if (dayStep) tick.plusDays(stepMs / DAY_MS) else tick.plusNanos(stepMs * 1_000_000)

    private companion object {
        const val MAX_GRIDLINES = 7
        const val DAY_MS = 24L * 60 * 60 * 1000
        val GRID_STEPS_MS = longArrayOf(
            60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L,
            3_600_000L, 3 * 3_600_000L, 6 * 3_600_000L, 12 * 3_600_000L,
            DAY_MS, 2 * DAY_MS, 7 * DAY_MS,
        ).toList()
    }
}

/**
 * The chart's date/time stamps in [locale]'s own field order and month names
 * (24-hour, like the rest of the app's charts). Zone-free: callers format a
 * ZonedDateTime in the current zone.
 */
private class TimelineFormats(locale: Locale) {
    val gridTime: DateTimeFormatter = pattern(locale, "HHmm", "HH:mm")
    val gridDay: DateTimeFormatter = pattern(locale, "MMMd", "MMM d")
    val stampMinutes: DateTimeFormatter = pattern(locale, "MMMdHHmm", "MMM d, HH:mm")
    val stampSeconds: DateTimeFormatter = pattern(locale, "MMMdHHmmss", "MMM d, HH:mm:ss")

    private companion object {
        /**
         * The platform's best pattern for [skeleton] in [locale]. ICU patterns
         * are java.time-compatible for these fields; the US pattern is the
         * fallback should a locale ever produce one that is not.
         */
        fun pattern(locale: Locale, skeleton: String, fallback: String): DateTimeFormatter =
            runCatching {
                DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            }.getOrElse { DateTimeFormatter.ofPattern(fallback, Locale.US) }
    }
}

/** Same memo as PerfChart's private ceiling cache; local copy keeps both private. */
private class CeilingLabelCache2 {
    private var text: String? = null
    private var layout: TextLayoutResult? = null

    fun layoutFor(
        text: String,
        measurer: TextMeasurer,
        style: TextStyle,
    ): TextLayoutResult {
        val cached = layout
        if (cached != null && text == this.text) return cached
        return measurer.measure(text, style).also {
            this.text = text
            layout = it
        }
    }
}
