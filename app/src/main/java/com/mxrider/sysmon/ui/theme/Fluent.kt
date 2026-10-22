package com.mxrider.sysmon.ui.theme

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.AccentOption
import com.mxrider.sysmon.core.ThemeMode

/**
 * Windows 11 Fluent-inspired design tokens. Components must consume
 * [FluentTheme.colors] / [FluentTheme.typography] / [FluentMetrics] — never
 * hardcode colors or Material defaults directly.
 */

@Immutable
data class FluentColors(
    val isDark: Boolean,
    /** Mica-style window background. */
    val window: Color,
    /** Card / layer surfaces sitting on the window. */
    val card: Color,
    val cardStroke: Color,
    /** Slightly recessed layer (e.g. table header, rail). */
    val layer: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    /** Validated series/chart accent for this mode. */
    val accent: Color,
    /** Win11 control-fill accent (buttons, toggles, selection pill). */
    val accentControl: Color,
    /** Text/icon color drawn on top of [accentControl]. */
    val onAccentControl: Color,
    /** Hover/pressed subtle fills for list rows and icon buttons. */
    val subtleHover: Color,
    val subtlePressed: Color,
    /** Selected list-row fill. */
    val selectionFill: Color,
    val controlFill: Color,
    val controlStroke: Color,
    val focusStroke: Color,
    val danger: Color,
    val caution: Color,
    val success: Color,
    /* Chart tokens (contrast-checked alphas over accent). */
    val chartFill: Color,
    val chartGrid: Color,
    val chartBorder: Color,
)

fun fluentLightColors(accent: Color, accentControl: Color): FluentColors = FluentColors(
    isDark = false,
    window = Color(0xFFF3F3F3),
    card = Color(0xFFFBFBFB),
    cardStroke = Color(0xFFE5E5E5),
    layer = Color(0xFFF9F9F9),
    divider = Color(0xFFE0E0E0),
    textPrimary = Color(0xFF1B1B1B),
    textSecondary = Color(0xFF5D5D5D),
    textTertiary = Color(0xFF868686),
    textDisabled = Color(0xFF9D9D9D),
    accent = accent,
    accentControl = accentControl,
    onAccentControl = Color.White,
    subtleHover = Color(0x0A000000),
    subtlePressed = Color(0x12000000),
    selectionFill = Color(0x0F000000),
    controlFill = Color(0xFFFDFDFD),
    controlStroke = Color(0xFFD8D8D8),
    focusStroke = Color(0xFF1B1B1B),
    danger = Color(0xFFC42B1C),
    caution = Color(0xFF9D5D00),
    success = Color(0xFF0F7B0F),
    chartFill = accent.copy(alpha = 0.08f),
    chartGrid = accent.copy(alpha = 0.14f),
    chartBorder = accent.copy(alpha = 0.40f),
)

fun fluentDarkColors(accent: Color, accentControl: Color): FluentColors = FluentColors(
    isDark = true,
    window = Color(0xFF202020),
    card = Color(0xFF2B2B2B),
    cardStroke = Color(0xFF1D1D1D),
    layer = Color(0xFF282828),
    divider = Color(0xFF3A3A3A),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFC8C8C8),
    textTertiary = Color(0xFF969696),
    textDisabled = Color(0xFF717171),
    accent = accent,
    accentControl = accentControl,
    onAccentControl = Color(0xFF000000),
    subtleHover = Color(0x0FFFFFFF),
    subtlePressed = Color(0x17FFFFFF),
    selectionFill = Color(0x14FFFFFF),
    controlFill = Color(0xFF333333),
    controlStroke = Color(0xFF454545),
    focusStroke = Color(0xFFFFFFFF),
    danger = Color(0xFFFF99A4),
    caution = Color(0xFFFCE100),
    success = Color(0xFF6CCB5F),
    chartFill = accent.copy(alpha = 0.16f),
    chartGrid = accent.copy(alpha = 0.20f),
    chartBorder = accent.copy(alpha = 0.45f),
)

/* -------------------------------- Typography ------------------------------- */

val Selawik = FontFamily(
    Font(R.font.selawkl, FontWeight.Light),
    Font(R.font.selawksl, FontWeight(350)),
    Font(R.font.selawk, FontWeight.Normal),
    Font(R.font.selawksb, FontWeight.SemiBold),
    Font(R.font.selawkb, FontWeight.Bold),
)

@Immutable
data class FluentTypography(
    /** 12sp — table headers, chart axis labels, captions. */
    val caption: TextStyle,
    /** 14sp — default body/cell text. */
    val body: TextStyle,
    /** 14sp semibold — emphasized body, column of interest. */
    val bodyStrong: TextStyle,
    /** 16sp semibold — card titles, stat values secondary. */
    val subtitleSmall: TextStyle,
    /** 20sp semibold — section subtitles ("CPU", chart panel titles). */
    val subtitle: TextStyle,
    /** 28sp semibold — page titles ("Processes"). */
    val title: TextStyle,
    /** 32sp semibold, tabular numerals — big live values ("47%"). */
    val statValue: TextStyle,
)

fun fluentTypography(): FluentTypography {
    val base = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.Normal)
    return FluentTypography(
        caption = base.copy(fontSize = 12.sp, lineHeight = 16.sp),
        body = base.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyStrong = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        subtitleSmall = base.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        subtitle = base.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        title = base.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
        statValue = base.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    )
}

/* --------------------------------- Metrics -------------------------------- */

object FluentMetrics {
    val railWidth: Dp = 48.dp
    val railExpandedWidth: Dp = 260.dp
    val railItemHeight: Dp = 40.dp
    val controlHeight: Dp = 32.dp
    val rowHeight: Dp = 40.dp
    val tableHeaderHeight: Dp = 36.dp
    val pagePadding: Dp = 16.dp
    val cardCorner: Dp = 8.dp
    val controlCorner: Dp = 4.dp
    val cardPadding: Dp = 16.dp
}

/* ---------------------------------- Theme --------------------------------- */

val LocalFluentColors = staticCompositionLocalOf { fluentLightColors(Color(0xFF106CBC), Color(0xFF0F6CBD)) }
val LocalFluentTypography = staticCompositionLocalOf { fluentTypography() }

object FluentTheme {
    val colors: FluentColors
        @Composable @ReadOnlyComposable get() = LocalFluentColors.current
    val typography: FluentTypography
        @Composable @ReadOnlyComposable get() = LocalFluentTypography.current
}

private fun bridgeColorScheme(c: FluentColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accentControl,
        onPrimary = c.onAccentControl,
        background = c.window,
        onBackground = c.textPrimary,
        surface = c.card,
        onSurface = c.textPrimary,
        surfaceVariant = c.layer,
        onSurfaceVariant = c.textSecondary,
        outline = c.controlStroke,
        outlineVariant = c.divider,
        error = c.danger,
    )
}

/** androidx.activity's default navigation-bar scrims (used on Android 8-9 only). */
private val LIGHT_NAV_SCRIM = 0xE6FFFFFF.toInt()
private val DARK_NAV_SCRIM = 0x801B1B1B.toInt()

@Composable
fun FluentTheme(
    themeMode: ThemeMode,
    accent: AccentOption,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Material You: swap only the two accent inputs for wallpaper-derived
    // tones and let the validated Fluent builders derive everything else.
    // Tone choices mirror the fixed palette's contrast pattern — light mode
    // pairs a dark accent with white foregrounds (accent1_600), dark mode a
    // pale control fill with black text (accent1_200) and a mid-light chart
    // accent (accent1_300). Below API 31 the DYNAMIC entry's baked-in BLUE
    // constants apply via the fallback branch.
    val dynamic = accent == AccentOption.DYNAMIC && Build.VERSION.SDK_INT >= 31
    val colors = if (dark) {
        fluentDarkColors(
            accent = if (dynamic) {
                colorResource(android.R.color.system_accent1_300)
            } else {
                Color(accent.darkArgb)
            },
            accentControl = if (dynamic) {
                colorResource(android.R.color.system_accent1_200)
            } else {
                Color(accent.controlDarkArgb)
            },
        )
    } else {
        fluentLightColors(
            accent = if (dynamic) {
                colorResource(android.R.color.system_accent1_600)
            } else {
                Color(accent.lightArgb)
            },
            accentControl = if (dynamic) {
                colorResource(android.R.color.system_accent1_600)
            } else {
                Color(accent.controlLightArgb)
            },
        )
    }
    val typography = fluentTypography()

    // The in-app theme can disagree with the system theme (Light setting on a
    // dark-mode phone), and the default enableEdgeToEdge follows the SYSTEM
    // uiMode: the status-bar clock/icons would render white over the app's
    // light surfaces (invisible), and on Android 8-9 the dark theme would get
    // white navigation buttons on the light scrim. Bar icons and scrims must
    // follow the theme actually drawn underneath them. In SYSTEM mode a
    // runtime dark-mode flip recomposes this (isSystemInDarkTheme) and
    // re-runs it; forced modes never need to.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? ComponentActivity)?.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ) { dark },
                navigationBarStyle = SystemBarStyle.auto(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM) { dark },
            )
        }
    }

    CompositionLocalProvider(
        LocalFluentColors provides colors,
        LocalFluentTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = bridgeColorScheme(colors),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(FluentMetrics.controlCorner),
                small = RoundedCornerShape(FluentMetrics.controlCorner),
                medium = RoundedCornerShape(FluentMetrics.cardCorner),
                large = RoundedCornerShape(FluentMetrics.cardCorner),
            ),
            typography = androidx.compose.material3.Typography(
                bodyLarge = typography.body,
                bodyMedium = typography.body,
                bodySmall = typography.caption,
                titleLarge = typography.title,
                titleMedium = typography.subtitle,
                titleSmall = typography.subtitleSmall,
                labelLarge = typography.bodyStrong,
                labelMedium = typography.caption,
                labelSmall = typography.caption,
            ),
            content = content,
        )
    }
}
