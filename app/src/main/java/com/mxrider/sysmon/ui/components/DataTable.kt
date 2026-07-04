package com.mxrider.sysmon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.R
import com.mxrider.sysmon.ui.theme.FluentMetrics
import com.mxrider.sysmon.ui.theme.FluentTheme

/**
 * Column description for [DataTable].
 *
 * @param key stable identifier reported through `onSortChange`.
 * @param title header caption.
 * @param width fixed column width; the table scrolls horizontally when the
 *   sum of all widths exceeds the viewport.
 * @param alignEnd right-align cell/header content (numeric columns, Win11 style).
 * @param comparator non-null makes the column sortable. The table itself never
 *   applies the comparator — it only decorates the header and reports clicks.
 * @param cell composable rendering one cell for a row value.
 */
data class TableColumn<T>(
    val key: String,
    val title: String,
    val width: Dp,
    val alignEnd: Boolean = false,
    val comparator: Comparator<T>? = null,
    val cell: @Composable (T) -> Unit,
)

/**
 * Win11-style data table: card container, 36dp caption header with sort
 * chevron, 40dp rows in a LazyColumn, horizontal scrolling that keeps header
 * and body columns aligned (both are laid out at the same fixed total width).
 *
 * Sorting is the CALLER's job: the table never reorders [rows]. When a
 * sortable header is tapped it invokes [onSortChange] with the column key and
 * the requested direction (tapping the already-sorted column toggles
 * [ascending]; tapping a new column requests ascending). The caller sorts its
 * list — typically with the column's [TableColumn.comparator] — and passes
 * the result back in along with [sortKey]/[ascending].
 *
 * Selection: tapping a row calls [onSelect] with the row, tapping the selected
 * row again calls it with `null`. [selectedKey] is compared against
 * [rowKey] of each row.
 *
 * The table must be given a bounded height (e.g. `Modifier.weight(1f)` or
 * `fillMaxSize`); the row list fills the remaining height. An empty [rows]
 * list renders just the header — callers overlay their own [EmptyState].
 */
@Composable
fun <T> DataTable(
    columns: List<TableColumn<T>>,
    rows: List<T>,
    rowKey: (T) -> Any,
    modifier: Modifier = Modifier,
    sortKey: String? = null,
    ascending: Boolean = true,
    onSortChange: ((key: String, ascending: Boolean) -> Unit)? = null,
    selectedKey: Any? = null,
    onSelect: ((T?) -> Unit)? = null,
) {
    val colors = FluentTheme.colors
    val shape = RoundedCornerShape(FluentMetrics.cardCorner)
    val totalWidth = columns.fold(0.dp) { acc, column -> acc + column.width }
    val horizontalState = rememberScrollState()

    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.cardStroke, shape)
            // The scroll modifier sits inside the border/background, so the
            // card chrome stays fixed while header + body scroll together.
            .horizontalScroll(horizontalState),
    ) {
        Row(
            modifier = Modifier
                .width(totalWidth)
                .height(FluentMetrics.tableHeaderHeight),
        ) {
            columns.forEach { column ->
                HeaderCell(
                    column = column,
                    sortKey = sortKey,
                    ascending = ascending,
                    onSortChange = onSortChange,
                )
            }
        }
        Box(
            modifier = Modifier
                .width(totalWidth)
                .height(1.dp)
                .background(colors.divider),
        )
        LazyColumn(
            modifier = Modifier
                .width(totalWidth)
                .weight(1f),
        ) {
            items(items = rows, key = rowKey) { item ->
                TableRow(
                    columns = columns,
                    item = item,
                    selected = selectedKey != null && rowKey(item) == selectedKey,
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun <T> HeaderCell(
    column: TableColumn<T>,
    sortKey: String?,
    ascending: Boolean,
    onSortChange: ((key: String, ascending: Boolean) -> Unit)?,
) {
    val colors = FluentTheme.colors
    val sorted = sortKey == column.key
    val sortedAscending = stringResource(R.string.components_sorted_ascending)
    val sortedDescending = stringResource(R.string.components_sorted_descending)

    val clickModifier = if (column.comparator != null && onSortChange != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClick = { onSortChange(column.key, if (sorted) !ascending else true) },
        )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .width(column.width)
            .fillMaxHeight()
            .then(clickModifier)
            .semantics {
                if (sorted) {
                    stateDescription = if (ascending) sortedAscending else sortedDescending
                }
            }
            .padding(horizontal = 12.dp),
        contentAlignment = if (column.alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = column.title,
                style = FluentTheme.typography.caption,
                color = colors.textSecondary,
                maxLines = 1,
            )
            if (sorted) {
                Icon(
                    imageVector = if (ascending) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun <T> TableRow(
    columns: List<TableColumn<T>>,
    item: T,
    selected: Boolean,
    onSelect: ((T?) -> Unit)?,
) {
    val colors = FluentTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background = when {
        selected -> colors.selectionFill
        pressed -> colors.subtleHover
        else -> Color.Transparent
    }
    val selectModifier = if (onSelect != null) {
        Modifier.selectable(
            selected = selected,
            interactionSource = interactionSource,
            indication = null,
            onClick = { onSelect(if (selected) null else item) },
        )
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .height(FluentMetrics.rowHeight)
            .background(background)
            .then(selectModifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            Box(
                modifier = Modifier
                    .width(column.width)
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp),
                contentAlignment = if (column.alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                column.cell(item)
            }
        }
    }
}
