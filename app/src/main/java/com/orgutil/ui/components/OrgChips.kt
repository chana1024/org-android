package com.orgutil.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.orgutil.ui.theme.LocalExtendedColors
import com.orgutil.ui.theme.OrgMono

/**
 * Shared org-mode grammar chips ("OrgUtil Teal Light"):
 * state keywords, priorities and tags rendered the same way everywhere
 * (agenda, org renderer, editor).
 */

/** Whether a TODO keyword marks a finished item (strikethrough consumers). */
fun orgStateIsDone(state: String): Boolean = state.uppercase() == "DONE"

/** Whether a TODO keyword marks a cancelled item (dim consumers). */
fun orgStateIsCancelled(state: String): Boolean =
    state.uppercase() in setOf("CANCELLED", "CANCELED")

/** Whether a TODO keyword is in active progress (teal family). */
fun orgStateIsDoing(state: String): Boolean =
    state.uppercase() in setOf("DOING", "IN-PROGRESS", "STARTED")

/**
 * TODO keyword chip: one distinctive hue per Doom keyword — TODO neutral,
 * DOING teal, VIBING cyan, NEXT blue, HOLD steel, WAIT/WAITING amber,
 * SANDBAGGING brown, DONE green, AREA olive, PROJ violet, MAYBE
 * lavender-grey, DROPPED rose, CANCELLED dim grey — so no two common states
 * share a colour wherever the chip renders (agenda, org renderer, editor).
 */
@Composable
fun OrgStateChip(
    state: String,
    modifier: Modifier = Modifier
) {
    val extended = LocalExtendedColors.current
    val (container, content) = when (state.uppercase()) {
        "DONE" ->
            extended.successContainer to extended.onSuccessContainer
        "DOING", "IN-PROGRESS", "STARTED" ->
            extended.doingContainer to extended.onDoingContainer
        "VIBING" ->
            extended.vibingContainer to extended.onVibingContainer
        "NEXT" ->
            extended.infoContainer to extended.onInfoContainer
        "TODO" ->
            extended.todoContainer to extended.onTodoContainer
        "WAIT", "WAITING" ->
            extended.warningContainer to extended.onWarningContainer
        "HOLD" ->
            extended.holdContainer to extended.onHoldContainer
        "SANDBAGGING" ->
            extended.sandbaggingContainer to extended.onSandbaggingContainer
        "PROJ" ->
            extended.projectContainer to extended.onProjectContainer
        "AREA" ->
            extended.areaContainer to extended.onAreaContainer
        "MAYBE" ->
            extended.maybeContainer to extended.onMaybeContainer
        "DROPPED" ->
            extended.droppedContainer to extended.onDroppedContainer
        "CANCELLED", "CANCELED" ->
            // Dim grey chip; Classic defaults are the exact historic
            // outlineVariant/onSurface pair, Kraft swaps in the light base.
            extended.cancelledContainer to extended.onCancelledContainer
        else ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = container,
        contentColor = content
    ) {
        Text(
            text = state,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Priority chip: `#A` red-orange, `#B` amber, `#C` neutral — monospace
 * per the org syntax grammar.
 */
@Composable
fun PriorityChip(
    priority: String,
    modifier: Modifier = Modifier
) {
    val extended = LocalExtendedColors.current
    val (container, content) = when (priority.uppercase()) {
        "A" -> extended.priorityAContainer to extended.onPriorityAContainer
        "B" -> extended.priorityBContainer to extended.onPriorityBContainer
        "C" -> extended.priorityCContainer to extended.onPriorityCContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = container,
        contentColor = content
    ) {
        Text(
            text = "#$priority",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** `:tag:` chip in the neutral container. */
@Composable
fun TagChip(
    tag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(
            text = ":$tag:",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}
