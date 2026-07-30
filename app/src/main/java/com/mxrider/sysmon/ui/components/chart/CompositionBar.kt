package com.mxrider.sysmon.ui.components.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.ui.theme.FluentTheme
import kotlin.math.roundToInt

/** Segments smaller than this are skipped (invisible slivers). */
private const val MIN_VISIBLE_FRACTION = 0.001f

/**
 * One ordered segment of a [CompositionBar]. [fraction] is 0..1 of the whole
 * bar; fractions of all segments should sum to <= 1 — the remainder renders as
 * free space in the recessed layer fill.
 */
data class BarSegment(val fraction: Float, val color: Color, val label: String)

/**
 * Win11 "Memory composition" bar: a 24dp tall rounded bar of ordered weighted
 * segments with 2dp gaps, plus a wrapping caption legend underneath. Identity
 * is carried by the labels, never by color alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompositionBar(
    segments: List<BarSegment>,
    modifier: Modifier = Modifier,
) {
    val colors = FluentTheme.colors
    val typography = FluentTheme.typography

    val visible = remember(segments) { segments.filter { it.fraction > MIN_VISIBLE_FRACTION } }
    val remainder = remember(visible) {
        1f - visible.fold(0f) { acc, segment -> acc + segment.fraction }
    }

    // The legend below reads each label, but the PROPORTIONS live only in the
    // painted widths — summarize them so TalkBack users get what sighted users
    // see at a glance.
    val summary = remember(visible) {
        visible.joinToString { segment ->
            "${segment.label} — ${(segment.fraction * 100).roundToInt()}%"
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.layer)
                .semantics { contentDescription = summary },
        ) {
            visible.forEachIndexed { index, segment ->
                if (index > 0) Spacer(Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .weight(segment.fraction)
                        .fillMaxHeight()
                        .background(segment.color),
                )
            }
            if (remainder > MIN_VISIBLE_FRACTION) {
                Box(
                    modifier = Modifier
                        .weight(remainder)
                        .fillMaxHeight()
                        .background(colors.layer),
                )
            } else if (visible.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(colors.layer),
                )
            }
        }

        if (visible.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                visible.forEach { segment ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(segment.color)
                                .border(1.dp, colors.cardStroke),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = segment.label,
                            style = typography.caption,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}
