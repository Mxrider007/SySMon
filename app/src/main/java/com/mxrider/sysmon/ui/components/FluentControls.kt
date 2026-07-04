package com.mxrider.sysmon.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mxrider.sysmon.R
import com.mxrider.sysmon.ui.theme.FluentMetrics
import com.mxrider.sysmon.ui.theme.FluentTheme

/**
 * Windows 11 Fluent-style basic controls. All controls are 32dp tall with
 * 4dp corners and consume [FluentTheme] tokens exclusively.
 */

/** Accent-filled primary button. `danger = true` uses the theme danger fill. */
@Composable
fun FluentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val colors = FluentTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val base = when {
        !enabled -> colors.controlFill
        danger -> colors.danger
        else -> colors.accentControl
    }
    val background = if (pressed && enabled) base.copy(alpha = 0.9f) else base
    // onAccentControl is white in light mode and black in dark mode, matching
    // Win11's text-on-accent (and text-on-critical) fills in both themes.
    val textColor = if (enabled) colors.onAccentControl else colors.textDisabled

    Box(
        modifier = modifier
            .height(FluentMetrics.controlHeight)
            .defaultMinSize(minWidth = 64.dp)
            .clip(RoundedCornerShape(FluentMetrics.controlCorner))
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = FluentTheme.typography.body,
            color = textColor,
            maxLines = 1,
        )
    }
}

/** Neutral button: controlFill background with a 1dp controlStroke border. */
@Composable
fun FluentStandardButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val colors = FluentTheme.colors
    val shape = RoundedCornerShape(FluentMetrics.controlCorner)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background = if (pressed && enabled) {
        colors.subtlePressed.compositeOver(colors.controlFill)
    } else {
        colors.controlFill
    }
    val contentColor = if (enabled) colors.textPrimary else colors.textDisabled

    Row(
        modifier = modifier
            .height(FluentMetrics.controlHeight)
            .defaultMinSize(minWidth = 64.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, colors.controlStroke, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = FluentTheme.typography.body,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/**
 * Win11 toggle switch: 40x20dp pill track. Off = outlined track with a 12dp
 * knob; on = accent track with a 14dp knob. Touch target is 40x32dp.
 */
@Composable
fun FluentToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = FluentTheme.colors
    val trackShape = RoundedCornerShape(10.dp)
    val knobSize by animateDpAsState(
        targetValue = if (checked) 14.dp else 12.dp,
        animationSpec = tween(durationMillis = 150),
        label = "toggleKnobSize",
    )
    // Off: 4dp inset from the left. On: 3dp inset from the right (40 - 14 - 3).
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 23.dp else 4.dp,
        animationSpec = tween(durationMillis = 150),
        label = "toggleKnobOffset",
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.accentControl else Color.Transparent,
        animationSpec = tween(durationMillis = 150),
        label = "toggleTrack",
    )
    val knobColor = if (checked) colors.onAccentControl else colors.textSecondary

    Box(
        modifier = modifier
            .size(width = 40.dp, height = 32.dp)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 20.dp)
                .alpha(if (enabled) 1f else 0.45f)
                .clip(trackShape)
                .background(trackColor)
                .border(
                    width = 1.dp,
                    color = if (checked) Color.Transparent else colors.controlStroke,
                    shape = trackShape,
                ),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = knobOffset)
                    .size(knobSize)
                    .background(knobColor, CircleShape),
            )
        }
    }
}

/** Win11 combo box: standard-button-style control opening a menu of options. */
@Composable
fun <T> FluentDropDown(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = FluentTheme.colors
    val shape = RoundedCornerShape(FluentMetrics.controlCorner)
    var expanded by remember { mutableStateOf(false) }

    Box(modifier) {
        Row(
            modifier = Modifier
                .height(FluentMetrics.controlHeight)
                .clip(shape)
                .background(colors.controlFill)
                .border(1.dp, colors.controlStroke, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.DropdownList,
                    onClick = { expanded = true },
                )
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label(selected),
                style = FluentTheme.typography.body,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label(option),
                            style = if (option == selected) {
                                FluentTheme.typography.bodyStrong
                            } else {
                                FluentTheme.typography.body
                            },
                            color = colors.textPrimary,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/**
 * Win11 search box: 32dp control with a magnifier icon; while focused a 2dp
 * accent line is drawn along the bottom edge.
 */
@Composable
fun SearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
) {
    val colors = FluentTheme.colors
    val shape = RoundedCornerShape(FluentMetrics.controlCorner)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val underline = colors.accentControl

    Row(
        modifier = modifier
            .height(FluentMetrics.controlHeight)
            .clip(shape)
            .background(colors.controlFill)
            // drawWithContent sits before border so the focus underline paints
            // over the 1dp stroke at the bottom edge.
            .drawWithContent {
                drawContent()
                if (focused) {
                    val lineHeight = 2.dp.toPx()
                    drawRect(
                        color = underline,
                        topLeft = Offset(0f, size.height - lineHeight),
                        size = Size(size.width, lineHeight),
                    )
                }
            }
            .border(1.dp, colors.controlStroke, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    style = FluentTheme.typography.body,
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = FluentTheme.typography.body.copy(color = colors.textPrimary),
                singleLine = true,
                cursorBrush = SolidColor(colors.accent),
                interactionSource = interactionSource,
            )
        }
    }
}

/** Centered icon + title + body, with an optional primary action button. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = FluentTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = title,
            style = FluentTheme.typography.subtitle,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = FluentTheme.typography.body,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            FluentButton(text = actionText, onClick = onAction)
        }
    }
}

/**
 * Win11 content dialog: card surface, 8dp corners, 1dp stroke; trailing
 * Cancel + confirm buttons. Dismiss on scrim tap / back is wired to
 * [onDismiss].
 */
@Composable
fun FluentDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = FluentTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier.widthIn(max = 420.dp),
            shape = RoundedCornerShape(FluentMetrics.cardCorner),
            color = colors.card,
            border = BorderStroke(1.dp, colors.cardStroke),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    style = FluentTheme.typography.subtitle,
                    color = colors.textPrimary,
                )
                if (body != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = body,
                        style = FluentTheme.typography.body,
                        color = colors.textSecondary,
                    )
                }
                content()
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    FluentStandardButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = onDismiss,
                    )
                    FluentButton(
                        text = confirmText,
                        onClick = onConfirm,
                        danger = danger,
                    )
                }
            }
        }
    }
}

/** Small stat: caption label over a subtitleSmall value, optional third line. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
) {
    val colors = FluentTheme.colors
    Column(modifier) {
        Text(
            text = label,
            style = FluentTheme.typography.caption,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = FluentTheme.typography.subtitleSmall,
            color = colors.textPrimary,
        )
        if (secondary != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = secondary,
                style = FluentTheme.typography.caption,
                color = colors.textTertiary,
            )
        }
    }
}

/** Card surface: card fill, 1dp cardStroke border, 8dp corners, cardPadding. */
@Composable
fun FluentCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FluentTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(FluentMetrics.cardCorner),
        color = colors.card,
        border = BorderStroke(1.dp, colors.cardStroke),
    ) {
        Column(Modifier.padding(FluentMetrics.cardPadding), content = content)
    }
}
