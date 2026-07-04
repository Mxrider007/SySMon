package com.mxrider.sysmon.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MiscellaneousServices
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.TabDestination
import com.mxrider.sysmon.ui.theme.FluentMetrics
import com.mxrider.sysmon.ui.theme.FluentTheme

/** Main destinations in Win11 Task Manager order; Settings is pinned at the bottom separately. */
private val MainTabs = listOf(
    TabDestination.PROCESSES,
    TabDestination.PERFORMANCE,
    TabDestination.APP_HISTORY,
    TabDestination.STARTUP,
    TabDestination.USERS,
    TabDestination.DETAILS,
    TabDestination.SERVICES,
)

@StringRes
private fun labelRes(tab: TabDestination): Int = when (tab) {
    TabDestination.PROCESSES -> R.string.nav_processes
    TabDestination.PERFORMANCE -> R.string.nav_performance
    TabDestination.APP_HISTORY -> R.string.nav_app_history
    TabDestination.STARTUP -> R.string.nav_startup
    TabDestination.USERS -> R.string.nav_users
    TabDestination.DETAILS -> R.string.nav_details
    TabDestination.SERVICES -> R.string.nav_services
    TabDestination.SETTINGS -> R.string.nav_settings
}

private fun iconFor(tab: TabDestination): ImageVector = when (tab) {
    TabDestination.PROCESSES -> Icons.Outlined.GridView
    TabDestination.PERFORMANCE -> Icons.AutoMirrored.Outlined.ShowChart
    TabDestination.APP_HISTORY -> Icons.Outlined.History
    TabDestination.STARTUP -> Icons.Outlined.RocketLaunch
    TabDestination.USERS -> Icons.Outlined.Person
    TabDestination.DETAILS -> Icons.AutoMirrored.Outlined.List
    TabDestination.SERVICES -> Icons.Outlined.MiscellaneousServices
    TabDestination.SETTINGS -> Icons.Outlined.Settings
}

/**
 * Windows 11 Task Manager left navigation rail. Collapsed it is a 48dp icon
 * column on the window background; expanded it animates to 260dp with
 * icon+label rows on a card surface with a 1dp right border.
 */
@Composable
fun NavRail(
    selected: TabDestination,
    onSelect: (TabDestination) -> Unit,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FluentTheme.colors
    val width by animateDpAsState(
        targetValue = if (expanded) FluentMetrics.railExpandedWidth else FluentMetrics.railWidth,
        animationSpec = tween(durationMillis = 200),
        label = "railWidth",
    )
    val background by animateColorAsState(
        targetValue = if (expanded) colors.card else colors.window,
        animationSpec = tween(durationMillis = 200),
        label = "railBackground",
    )
    val borderColor = colors.cardStroke

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .background(background)
            .drawBehind {
                if (expanded) {
                    val stroke = 1.dp.toPx()
                    drawLine(
                        color = borderColor,
                        start = Offset(size.width - stroke / 2f, 0f),
                        end = Offset(size.width - stroke / 2f, size.height),
                        strokeWidth = stroke,
                    )
                }
            }
            .padding(vertical = 4.dp),
    ) {
        IconButton(
            onClick = onToggleExpand,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = stringResource(R.string.nav_menu),
                tint = colors.textPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        MainTabs.forEach { tab ->
            RailItem(
                icon = iconFor(tab),
                label = stringResource(labelRes(tab)),
                selected = tab == selected,
                expanded = expanded,
                onClick = { onSelect(tab) },
            )
        }
        Spacer(Modifier.weight(1f))
        RailItem(
            icon = iconFor(TabDestination.SETTINGS),
            label = stringResource(labelRes(TabDestination.SETTINGS)),
            selected = selected == TabDestination.SETTINGS,
            expanded = expanded,
            onClick = { onSelect(TabDestination.SETTINGS) },
        )
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = FluentTheme.colors
    val tint = if (selected) colors.textPrimary else colors.textSecondary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(FluentMetrics.railItemHeight)
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(FluentMetrics.controlCorner))
            .background(if (selected) colors.subtleHover else Color.Transparent)
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(width = 3.dp, height = 16.dp)
                    .background(colors.accentControl, RoundedCornerShape(1.5.dp)),
            )
        }
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = label,
                    style = FluentTheme.typography.body,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(18.dp),
            )
        }
    }
}
