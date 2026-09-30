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
 * TODO keyword chip: TODO neutral, DOING teal, WAITING amber, DONE green,
 * CANCELLED dim — per the design system state grammar.
 */
@Composable
fun OrgStateChip(
    state: String,
    modifier: Modifier = Modifier
) {
    val extended = LocalExtendedColors.current
    val (container, content) = when {
        orgStateIsDone(state) ->
            extended.successContainer to extended.onSuccessContainer
        orgStateIsCancelled(state) ->
            MaterialTheme.colorScheme.outlineVariant to MaterialTheme.colorScheme.onSurfaceVariant
        orgStateIsDoing(state) ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        state.uppercase() == "TODO" ->
            extended.todoContainer to extended.onTodoContainer
        state.uppercase() == "WAITING" ->
            extended.warningContainer to extended.onWarningContainer
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
