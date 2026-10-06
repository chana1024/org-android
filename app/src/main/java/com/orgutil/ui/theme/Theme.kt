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
import com.orgutil.data.datasource.ThemeChoice

/**
 * "OrgUtil Teal Light" — the fixed light palette from the Stitch design
 * system. Primary deliberately carries the teal accent (active states,
 * FAB, primary buttons, DOING) even though the design's raw JSON keeps
 * primary neutral: every teal element in the app is driven by the M3
 * primary role, so mapping teal here restyles them with zero call-site
 * changes. The neutral family moved to secondary/onSurfaceVariant/outline,
 * which is where the design uses neutrals.
 *
 * This is the CLASSIC (经典) theme; its values are frozen — every field of
 * [KraftLedgerScheme] is a Kraft Ledger token, never an edit here.
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
    val onPriorityCContainer: Color = Color(0xFF5C605E),
    // org TODO keyword containers — one distinctive hue per Doom keyword so
    // chips stay distinguishable wherever they render (agenda, org renderer,
    // editor). Tonal pairs follow the same container grammar as above.
    // Taken: TODO neutral, DOING teal, WAIT/WAITING amber, DONE green,
    // NEXT blue, CANCELLED dim grey; the rest extend the wheel.
    val vibingContainer: Color = Color(0xFFC0F0F4),
    val onVibingContainer: Color = Color(0xFF00363C),
    val holdContainer: Color = Color(0xFFBCC9DC),
    val onHoldContainer: Color = Color(0xFF0D1F30),
    val projectContainer: Color = Color(0xFFE7DDFF),
    val onProjectContainer: Color = Color(0xFF27125E),
    val areaContainer: Color = Color(0xFFE3E7C3),
    val onAreaContainer: Color = Color(0xFF333900),
    val maybeContainer: Color = Color(0xFFE2E1F0),
    val onMaybeContainer: Color = Color(0xFF2A2744),
    val sandbaggingContainer: Color = Color(0xFFDCCDAF),
    val onSandbaggingContainer: Color = Color(0xFF35280F),
    val droppedContainer: Color = Color(0xFFF9D7E0),
    val onDroppedContainer: Color = Color(0xFF4A0E26),
    // DOING keyword: used to lean on colorScheme.primaryContainer /
    // onPrimaryContainer. Promoted to explicit fields so the selection
    // container role stays free to carry a dedicated selection token per
    // theme (Kraft primaryContainer IS its selection color). Defaults are
    // the exact Classic primaryContainer pair — zero visual change.
    val doingContainer: Color = Color(0xFFDAE5E2),
    val onDoingContainer: Color = Color(0xFF0C2B27),
    // CANCELLED keyword: used to lean on outlineVariant / onSurface.
    // Same promotion, defaults = the exact Classic pair.
    val cancelledContainer: Color = Color(0xFFB0B2B0),
    val onCancelledContainer: Color = Color(0xFF303332),
    // Habit consistency graph: the missed-day red and the marker ink were
    // file-private constants in AgendaScreen; promoted so a theme can retint
    // without changing any shape/calculation. Defaults are the exact Classic
    // constants.
    val habitMissed: Color = Color(0xFF8C1D1A),
    val habitMarkerInk: Color = Color(0xFF172624),
    // Background of an agenda row with a pomodoro running on it. Classic is
    // the exact historic value (primary @ 10%); Kraft is a solid paper tone.
    val pomodoroRowActive: Color = Color(0xFF2AA198).copy(alpha = 0.10f),
    // Monochrome hairline used at reduced alpha (OrgRenderer property
    // drawer rail). Default = the exact historic constant.
    val faintDivider: Color = Color(0xFFB0B2B0),
    // Org directive text (OrgRenderer's OrgDirective), historically
    // onSurfaceVariant @ 70% over a surfaceVariant @ 30% wash. Default =
    // the exact historic Classic composite source. Kraft swaps in a
    // FULL-opacity secondary so the text clears 4.5:1 on the wash (the
    // 70%-alpha grammar composites to only 2.91:1 on kraft tones) while
    // still reading subdued next to full ink.
    val directiveText: Color = Color(0xFF5C605E).copy(alpha = 0.70f)
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

/**
 * KRAFT LEDGER (牛皮账本) — the kraft-paper palette. Token semantics:
 *  - page #D6D0C2 → background/surface; card/row #E4DFD3 → containers;
 *  - small overlay/label/input #EEEAE0 → surfaceContainerLowest AND the
 *    shared light base under every keyword/priority label so ink stays
 *    legible on selected/running rows;
 *  - grouping/header/nav #C8C1B1 → surfaceVariant (+ highest container);
 *    headers stay COLORED with ink text — never a dark header;
 *  - text #26231D, secondary #5A5446, hairline border #B5AD9B,
 *    strong border/focus #6E6757;
 *  - primary/button #34302A with onPrimary #F2EEE4;
 *  - selection #C9CDBE → primaryContainer.
 * Derived (not in the approved token list, coherent interpolations):
 * surfaceContainerHigh #DCD6C8 for menus/dialogs between card and page;
 * error family #963527/#E9D6CF — error TEXT is darkened one step from the
 * #A33F2E red (which stays the missed/A-priority keyword foreground on the
 * light base) so error body text clears 4.5:1 on the page tone.
 */
val KraftLedgerScheme = lightColorScheme(
    primary = Color(0xFF34302A),
    onPrimary = Color(0xFFF2EEE4),
    primaryContainer = Color(0xFFC9CDBE),
    onPrimaryContainer = Color(0xFF26231D),
    secondary = Color(0xFF5A5446),
    onSecondary = Color(0xFFF2EEE4),
    secondaryContainer = Color(0xFFE4DFD3),
    onSecondaryContainer = Color(0xFF26231D),
    tertiary = Color(0xFF85560A),
    onTertiary = Color(0xFFF2EEE4),
    tertiaryContainer = Color(0xFFEEEAE0),
    onTertiaryContainer = Color(0xFF85560A),
    background = Color(0xFFD6D0C2),
    onBackground = Color(0xFF26231D),
    surface = Color(0xFFD6D0C2),
    onSurface = Color(0xFF26231D),
    surfaceVariant = Color(0xFFC8C1B1),
    onSurfaceVariant = Color(0xFF5A5446),
    surfaceContainerLowest = Color(0xFFEEEAE0),
    surfaceContainerLow = Color(0xFFE4DFD3),
    surfaceContainer = Color(0xFFE4DFD3),
    surfaceContainerHigh = Color(0xFFDCD6C8),
    surfaceContainerHighest = Color(0xFFC8C1B1),
    outline = Color(0xFF6E6757),
    outlineVariant = Color(0xFFB5AD9B),
    error = Color(0xFF963527),
    onError = Color(0xFFF2EEE4),
    errorContainer = Color(0xFFE9D6CF),
    onErrorContainer = Color(0xFF6E2418)
)

/**
 * Kraft Ledger extended grammar: every TODO-keyword and priority label sits
 * on the shared light #EEEAE0 base with a saturated ink foreground —
 * NEXT #2B5A9A, DONE #386838, WAIT #85560A, VIBING #6D3F85, TODO #5A5446,
 * missed-red #A33F2E per the approved token list; the remaining keywords
 * keep their Classic hue families (DOING teal, HOLD steel, PROJ violet,
 * AREA olive, MAYBE lavender-grey, SANDBAGGING brown, DROPPED rose,
 * CANCELLED dim grey) deepened to ink-weight so each meaning stays distinct
 * and contrast-safe on the light base.
 */
val KraftLedgerExtendedColors = ExtendedColors(
    success = Color(0xFF386838),
    onSuccess = Color(0xFFF2EEE4),
    successContainer = Color(0xFFEEEAE0),
    onSuccessContainer = Color(0xFF386838),
    warning = Color(0xFF85560A),
    warningContainer = Color(0xFFEEEAE0),
    onWarningContainer = Color(0xFF85560A),
    info = Color(0xFF2B5A9A),
    infoContainer = Color(0xFFEEEAE0),
    onInfoContainer = Color(0xFF2B5A9A),
    todoContainer = Color(0xFFEEEAE0),
    onTodoContainer = Color(0xFF5A5446),
    priorityAContainer = Color(0xFFEEEAE0),
    onPriorityAContainer = Color(0xFFA33F2E),
    priorityBContainer = Color(0xFFEEEAE0),
    onPriorityBContainer = Color(0xFF85560A),
    priorityCContainer = Color(0xFFEEEAE0),
    onPriorityCContainer = Color(0xFF5A5446),
    doingContainer = Color(0xFFEEEAE0),
    onDoingContainer = Color(0xFF1E6B63),
    vibingContainer = Color(0xFFEEEAE0),
    onVibingContainer = Color(0xFF6D3F85),
    holdContainer = Color(0xFFEEEAE0),
    onHoldContainer = Color(0xFF33517A),
    projectContainer = Color(0xFFEEEAE0),
    onProjectContainer = Color(0xFF5A3E8F),
    areaContainer = Color(0xFFEEEAE0),
    onAreaContainer = Color(0xFF5C6B1E),
    maybeContainer = Color(0xFFEEEAE0),
    onMaybeContainer = Color(0xFF56517A),
    sandbaggingContainer = Color(0xFFEEEAE0),
    onSandbaggingContainer = Color(0xFF6E4E1E),
    droppedContainer = Color(0xFFEEEAE0),
    onDroppedContainer = Color(0xFF8F3552),
    cancelledContainer = Color(0xFFEEEAE0),
    onCancelledContainer = Color(0xFF6B675C),
    habitMissed = Color(0xFFA33F2E),
    habitMarkerInk = Color(0xFF26231D),
    pomodoroRowActive = Color(0xFFCFC7AE),
    faintDivider = Color(0xFFB5AD9B),
    directiveText = Color(0xFF5A5446)
)

/** Flip to re-enable the system-following dark theme. */
private const val ENABLE_DARK = false

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OrgUtilTheme(
    choice: ThemeChoice = ThemeChoice.CLASSIC,
    content: @Composable () -> Unit
) {
    // Kraft Ledger is a light-only design; the (currently disabled) dark
    // branch belongs to Classic alone.
    val darkTheme = ENABLE_DARK && choice == ThemeChoice.CLASSIC && isSystemInDarkTheme()
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        choice == ThemeChoice.KRAFT_LEDGER -> KraftLedgerScheme
        else -> OrgUtilLightScheme
    }
    val extended = when {
        darkTheme -> ExtendedDarkColors
        choice == ThemeChoice.KRAFT_LEDGER -> KraftLedgerExtendedColors
        else -> ExtendedColors()
    }

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
