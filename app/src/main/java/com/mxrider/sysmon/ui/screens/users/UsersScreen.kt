package com.mxrider.sysmon.ui.screens.users

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.UserRow
import com.mxrider.sysmon.ui.components.EmptyState
import com.mxrider.sysmon.ui.components.FluentCard
import com.mxrider.sysmon.ui.components.TitleBar
import com.mxrider.sysmon.ui.theme.FluentMetrics
import com.mxrider.sysmon.ui.theme.FluentTheme

/**
 * Win11 Task Manager "Users" page, adapted to what Android actually exposes:
 * a card per user/profile visible to this app, with caption-chip badges for
 * Current / System / Work profile, and a footer explaining the limits.
 */
@Composable
fun UsersScreen(modifier: Modifier = Modifier) {
    val vm: UsersViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = FluentTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(FluentMetrics.pagePadding),
    ) {
        TitleBar(title = stringResource(R.string.nav_users))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 12.dp),
        ) {
            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accentControl)
                    }
                }
                state.users.isEmpty() -> {
                    EmptyState(
                        title = stringResource(R.string.users_empty_title),
                        body = stringResource(R.string.users_empty_body),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        icon = Icons.Outlined.Person,
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.users) { user ->
                            UserCard(user)
                        }
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.users_footer),
            style = FluentTheme.typography.caption,
            color = colors.textTertiary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun UserCard(user: UserRow) {
    val colors = FluentTheme.colors

    val name = user.name ?: if (user.handleId >= 0) {
        stringResource(R.string.users_user_fallback, user.handleId)
    } else {
        stringResource(R.string.users_user_unknown)
    }

    val currentLabel = stringResource(R.string.users_badge_current)
    val systemLabel = stringResource(R.string.users_badge_system)
    val workLabel = stringResource(R.string.users_badge_work)
    val badges = remember(user, currentLabel, systemLabel, workLabel) {
        buildList {
            if (user.isCurrent) add(currentLabel)
            if (user.isSystemUser) add(systemLabel)
            if (user.isManagedProfile) add(workLabel)
        }
    }

    FluentCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = FluentTheme.typography.subtitleSmall,
                    color = colors.textPrimary,
                )
                if (badges.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        badges.forEach { badge -> BadgeChip(badge) }
                    }
                }
            }
        }
    }
}

/** Caption chip: controlFill background, 1dp controlStroke, 4dp corners. */
@Composable
private fun BadgeChip(text: String) {
    val colors = FluentTheme.colors
    val shape = RoundedCornerShape(FluentMetrics.controlCorner)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(colors.controlFill)
            .border(1.dp, colors.controlStroke, shape),
    ) {
        Text(
            text = text,
            style = FluentTheme.typography.caption,
            color = colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
