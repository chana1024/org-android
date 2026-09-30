package com.orgutil.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Semantic colors the M3 scheme has no role for. Fixed pairs (light/dark)
 * chosen to sit well on both dynamic and expressive schemes — the standard
 * M3 "extend the scheme" pattern.
 */
data class ExtendedColors(
    val success: Color = Color(0xFF216C29),
    val onSuccess: Color = Color(0xFFFFFFFF),
    val successContainer: Color = Color(0xFFA9F2AB),
    val onSuccessContainer: Color = Color(0xFF002105),
    val warning: Color = Color(0xFF8B5000),
    val warningContainer: Color = Color(0xFFFFDCC2),
    val onWarningContainer: Color = Color(0xFF2E1500),
    val info: Color = Color(0xFF00639B),
    val infoContainer: Color = Color(0xFFCFE5FF),
    val onInfoContainer: Color = Color(0xFF001D34)
)

val LocalExtendedColors = staticCompositionLocalOf { ExtendedColors() }

/** Dark variants: desaturated tones that keep contrast on dark surfaces. */
private val ExtendedDarkColors = ExtendedColors(
    success = Color(0xFF8ED893),
    onSuccess = Color(0xFF00390B),
    successContainer = Color(0xFF005314),
    onSuccessContainer = Color(0xFFA9F2AB),
    warning = Color(0xFFFFB877),
    warningContainer = Color(0xFF6B3B00),
    onWarningContainer = Color(0xFFFFDCC2),
    info = Color(0xFF9ACBFF),
    infoContainer = Color(0xFF004A77),
    onInfoContainer = Color(0xFFCFE5FF)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OrgUtilTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        // material3 1.4.0 has no expressive dark scheme yet — dark keeps the
        // baseline scheme above; light falls back to the expressive one.
        else -> expressiveLightColorScheme()
    }
    val extended = if (darkTheme) ExtendedDarkColors else ExtendedColors()

    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = Typography,
            content = content
        )
    }
}
