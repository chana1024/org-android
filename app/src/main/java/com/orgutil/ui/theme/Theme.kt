package com.orgutil.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * "OrgUtil Teal Light" — the fixed light palette from the Stitch design
 * system. Primary deliberately carries the teal accent (active states,
 * FAB, primary buttons, DOING) even though the design's raw JSON keeps
 * primary neutral: every teal element in the app is driven by the M3
 * primary role, so mapping teal here restyles them with zero call-site
 * changes. The neutral family moved to secondary/onSurfaceVariant/outline,
 * which is where the design uses neutrals.
 */
val OrgUtilLightScheme = lightColorScheme(
    primary = Color(0xFF2AA198),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAE5E2),
    onPrimaryContainer = Color(0xFF0C2B27),
    secondary = Color(0xFF5C605E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E3E1),
    onSecondaryContainer = Color(0xFF1E2120),
    tertiary = Color(0xFFA06B00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6E3C2),
    onTertiaryContainer = Color(0xFF2E1500),
    background = Color(0xFFFBF9F7),
    onBackground = Color(0xFF303332),
    surface = Color(0xFFFBF9F7),
    onSurface = Color(0xFF303332),
    surfaceVariant = Color(0xFFE7E8E6),
    onSurfaceVariant = Color(0xFF5C605E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F3F2),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE7E8E6),
    surfaceContainerHighest = Color(0xFFE1E3E1),
    outline = Color(0xFF787B79),
    outlineVariant = Color(0xFFB0B2B0),
    error = Color(0xFFC4451A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DCCF),
    onErrorContainer = Color(0xFF3B0A02)
)

/** 8dp flat surfaces per the design (cards, chips, dialogs). */
val OrgUtilShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

/**
 * Semantic colors the M3 scheme has no role for — the standard M3
 * "extend the scheme" pattern. Includes the org-mode grammar: neutral
 * TODO, priority containers (#A red-orange, #B amber, #C neutral) and
 * success/warning/info for state feedback.
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
    val onInfoContainer: Color = Color(0xFF001D34),
    // org TODO keyword — neutral outlined chip.
    val todoContainer: Color = Color(0xFFECEDEC),
    val onTodoContainer: Color = Color(0xFF3C403E),
    // org priorities: #A red-orange, #B amber, #C neutral.
    val priorityAContainer: Color = Color(0xFFF9DCCF),
    val onPriorityAContainer: Color = Color(0xFF8F3113),
    val priorityBContainer: Color = Color(0xFFF6E3C2),
    val onPriorityBContainer: Color = Color(0xFF5C4300),
    val priorityCContainer: Color = Color(0xFFE7E8E6),
    val onPriorityCContainer: Color = Color(0xFF5C605E)
)

val LocalExtendedColors = staticCompositionLocalOf { ExtendedColors() }

/**
 * Dark variants kept for the (currently disabled) dark branch. New org
 * grammar fields inherit the light values — revisit if dark is enabled.
 */
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

/** Flip to re-enable the system-following dark theme. */
private const val ENABLE_DARK = false

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OrgUtilTheme(content: @Composable () -> Unit) {
    val darkTheme = ENABLE_DARK && isSystemInDarkTheme()
    val colorScheme = if (darkTheme) DarkColorScheme else OrgUtilLightScheme
    val extended = if (darkTheme) ExtendedDarkColors else ExtendedColors()

    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = Typography,
            shapes = OrgUtilShapes,
            content = content
        )
    }
}
