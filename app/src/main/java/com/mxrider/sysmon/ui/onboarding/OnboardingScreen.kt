package com.mxrider.sysmon.ui.onboarding

import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mxrider.sysmon.Graph
import com.mxrider.sysmon.R
import com.mxrider.sysmon.ui.components.FluentButton
import com.mxrider.sysmon.ui.components.FluentCard
import com.mxrider.sysmon.ui.components.FluentStandardButton
import com.mxrider.sysmon.ui.theme.FluentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Three-step first-run flow: welcome, prominent usage-access disclosure
 * (Play policy — grant is optional and revocable), done. While the disclosure
 * step is visible the grant state is re-polled every 1.5s so returning from
 * system settings auto-advances.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val colors = FluentTheme.colors
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(1) }
    var granted by rememberSaveable { mutableStateOf(false) }
    val openFailedMessage = stringResource(R.string.onboarding_open_settings_failed)

    LaunchedEffect(step) {
        if (step == 2) {
            while (true) {
                val has = withContext(Dispatchers.IO) {
                    runCatching { Graph.usageAccess.hasPermission() }.getOrDefault(false)
                }
                if (has) {
                    granted = true
                    delay(800)
                    step = 3
                    break
                }
                delay(1_500)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.window)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FluentCard(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .animateContentSize(tween(durationMillis = 180)),
            ) {
                Crossfade(
                    targetState = step,
                    animationSpec = tween(durationMillis = 180),
                    label = "onboardingStep",
                ) { current ->
                    when (current) {
                        1 -> WelcomeStep(onContinue = { step = 2 })
                        2 -> UsageAccessStep(
                            granted = granted,
                            onGrant = {
                                try {
                                    context.startActivity(Graph.usageAccess.settingsIntent())
                                } catch (_: Exception) {
                                    Toast
                                        .makeText(context, openFailedMessage, Toast.LENGTH_SHORT)
                                        .show()
                                }
                            },
                            onSkip = { step = 3 },
                        )
                        else -> DoneStep(granted = granted, onDone = onDone)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            StepDots(current = step)
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    val colors = FluentTheme.colors
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Monitor,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = FluentTheme.typography.subtitle,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Bullet(stringResource(R.string.onboarding_bullet_graphs))
            Bullet(stringResource(R.string.onboarding_bullet_processes))
            Bullet(stringResource(R.string.onboarding_bullet_startup))
            Bullet(stringResource(R.string.onboarding_bullet_private))
        }
        Spacer(Modifier.height(20.dp))
        FluentButton(
            text = stringResource(R.string.action_continue),
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Bullet(text: String) {
    val colors = FluentTheme.colors
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = "•",
            style = FluentTheme.typography.body,
            color = colors.textSecondary,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = text,
            style = FluentTheme.typography.body,
            color = colors.textPrimary,
        )
    }
}

/** Prominent disclosure for the special Usage access permission. */
@Composable
private fun UsageAccessStep(
    granted: Boolean,
    onGrant: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = FluentTheme.colors
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.QueryStats,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_usage_title),
            style = FluentTheme.typography.subtitle,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_usage_body_why),
            style = FluentTheme.typography.body,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_usage_body_private),
            style = FluentTheme.typography.body,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_usage_body_optional),
            style = FluentTheme.typography.body,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(16.dp))
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = colors.success,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.onboarding_usage_granted),
                    style = FluentTheme.typography.caption,
                    color = colors.success,
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FluentButton(
                    text = stringResource(R.string.onboarding_grant_usage),
                    onClick = onGrant,
                    modifier = Modifier.weight(1f),
                )
                FluentStandardButton(
                    text = stringResource(R.string.onboarding_skip),
                    onClick = onSkip,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DoneStep(granted: Boolean, onDone: () -> Unit) {
    val colors = FluentTheme.colors
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = colors.success,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_done_title),
            style = FluentTheme.typography.subtitle,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (granted) {
                    R.string.onboarding_done_body_granted
                } else {
                    R.string.onboarding_done_body_skipped
                },
            ),
            style = FluentTheme.typography.caption,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.onboarding_done_summary),
            style = FluentTheme.typography.caption,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FluentButton(
            text = stringResource(R.string.onboarding_get_started),
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StepDots(current: Int) {
    val colors = FluentTheme.colors
    val description = stringResource(R.string.onboarding_step_of, current)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        for (index in 1..3) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        color = if (index == current) colors.accentControl else colors.controlStroke,
                        shape = CircleShape,
                    ),
            )
        }
    }
}
