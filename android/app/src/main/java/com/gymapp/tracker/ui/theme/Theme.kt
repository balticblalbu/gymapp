package com.gymapp.tracker.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Design system of the app.
 *
 * Dark is the primary look (a gym is dark, the phone is held one-handed), light
 * is a first class citizen rather than an afterthought. Colours are defined
 * explicitly instead of using dynamic colour so brand identity stays stable.
 */

// --- Brand palette ---------------------------------------------------------
val Volt = Color(0xFFC8F751)
val VoltDim = Color(0xFFA6D42F)
val VoltInk = Color(0xFF4E7A00)

val PositiveDark = Color(0xFF5BE49B)
val NegativeDark = Color(0xFFFF6B6B)
val AccentBlue = Color(0xFF6AA9FF)
val PositiveLight = Color(0xFF2E7D4F)
val NegativeLight = Color(0xFFC43D3D)

private val DarkColors = darkColorScheme(
    primary = Volt,
    onPrimary = Color(0xFF0B0D11),
    primaryContainer = Color(0xFF20290C),
    onPrimaryContainer = Volt,
    secondary = AccentBlue,
    onSecondary = Color(0xFF04101F),
    background = Color(0xFF0B0D11),
    onBackground = Color(0xFFF2F5F9),
    surface = Color(0xFF141821),
    onSurface = Color(0xFFF2F5F9),
    surfaceVariant = Color(0xFF1B2029),
    onSurfaceVariant = Color(0xFF8A93A3),
    surfaceContainerHigh = Color(0xFF1B2029),
    outline = Color(0xFF262C38),
    outlineVariant = Color(0xFF1F2530),
    error = NegativeDark,
    onError = Color(0xFF2B0A0A),
)

private val LightColors = lightColorScheme(
    primary = VoltInk,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF9C4),
    onPrimaryContainer = Color(0xFF2C4600),
    secondary = Color(0xFF2C5FA8),
    onSecondary = Color.White,
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF10141B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10141B),
    surfaceVariant = Color(0xFFEDF0F6),
    onSurfaceVariant = Color(0xFF66707F),
    surfaceContainerHigh = Color(0xFFEDF0F6),
    outline = Color(0xFFDFE4EE),
    outlineVariant = Color(0xFFE8ECF3),
    error = NegativeLight,
    onError = Color.White,
)

/** Semantic colours that Material's scheme does not cover. */
data class AppColors(
    val positive: Color,
    val negative: Color,
    val chartLine: Color,
    val chartFillTop: Color,
    val chartFillBottom: Color,
    val chartGrid: Color,
    val barMuted: Color,
)

val LocalAppColors = staticCompositionLocalOf {
    AppColors(PositiveDark, NegativeDark, Volt, Volt.copy(alpha = 0.34f), Volt.copy(alpha = 0f), Color(0xFF262C38), Color(0xFF39445A))
}

private val darkExtras = AppColors(
    positive = PositiveDark,
    negative = NegativeDark,
    chartLine = Volt,
    chartFillTop = Volt.copy(alpha = 0.34f),
    chartFillBottom = Volt.copy(alpha = 0f),
    chartGrid = Color(0xFF262C38),
    barMuted = Color(0xFF39445A),
)

private val lightExtras = AppColors(
    positive = PositiveLight,
    negative = NegativeLight,
    chartLine = VoltDim,
    chartFillTop = VoltDim.copy(alpha = 0.30f),
    chartFillBottom = VoltDim.copy(alpha = 0f),
    chartGrid = Color(0xFFDFE4EE),
    barMuted = Color(0xFFC3CCDA),
)

/**
 * Numbers are the content of this app, so the type scale is tuned for them:
 * tight tracking on headlines, tabular figures wherever values are compared.
 */
private val AppTypography = Typography().run {
    val tightHeadline = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
    copy(
        displaySmall = displaySmall.merge(tightHeadline).copy(fontSize = 32.sp),
        headlineMedium = headlineMedium.merge(tightHeadline).copy(fontSize = 26.sp),
        headlineSmall = headlineSmall.merge(tightHeadline).copy(fontSize = 21.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelSmall = labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp),
        bodyMedium = bodyMedium.copy(lineHeight = 20.sp),
    )
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun GymAppTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (dark) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalAppColors provides if (dark) darkExtras else lightExtras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}

/** Shorthand: `AppTheme.colors.positive` */
object AppTheme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current
}
