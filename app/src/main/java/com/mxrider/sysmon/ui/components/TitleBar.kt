package com.mxrider.sysmon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.ui.theme.FluentTheme

/**
 * Page header: 28sp semibold title (Win11 Task Manager page title), an
 * optional secondary line below it, and an optional trailing toolbar row.
 *
 * Below [STACK_BREAKPOINT] the toolbar wraps to its own row under the title:
 * inline, a search box + button toolbar (~343dp) starves the weighted title
 * to zero width on phone-width panes and clips its own buttons — the page
 * name disappeared entirely on a 411dp device.
 */
@Composable
fun TitleBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (actions != null && maxWidth < STACK_BREAKPOINT) {
            Column(Modifier.fillMaxWidth()) {
                TitleBlock(title, subtitle, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TitleBlock(title, subtitle, Modifier.weight(1f))
                if (actions != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = actions,
                    )
                }
            }
        }
    }
}

@Composable
private fun TitleBlock(title: String, subtitle: String?, modifier: Modifier) {
    val colors = FluentTheme.colors
    Column(modifier) {
        Text(
            text = title,
            style = FluentTheme.typography.title,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = FluentTheme.typography.body,
                color = colors.textSecondary,
            )
        }
    }
}

/** Content widths under this stack the toolbar below the title. */
private val STACK_BREAKPOINT = 480.dp
