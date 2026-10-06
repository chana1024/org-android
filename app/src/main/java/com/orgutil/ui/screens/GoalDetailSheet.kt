package com.orgutil.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orgutil.data.mapper.OrgParserWrapper
import com.orgutil.domain.agenda.HabitDeadlineProximity
import com.orgutil.domain.agenda.habitDeadlineCountdown
import com.orgutil.domain.model.OrgNode
import com.orgutil.ui.components.OrgStateChip
import com.orgutil.ui.components.PriorityChip
import com.orgutil.ui.components.TagChip
import com.orgutil.ui.components.orgStateIsCancelled
import com.orgutil.ui.components.orgStateIsDone
import com.orgutil.ui.theme.LocalExtendedColors
import com.orgutil.ui.theme.OrgMono
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 总目标 detail sheet: the agenda's goal file (agenda_goal.org at the vault
 * root — the exact source OrgAgendaRepositoryImpl feeds OrgAgenda.goalText)
 * shown read-only as a full-height modal. Content is parsed with the same
 * OrgParserWrapper the file viewer uses and rendered with the shared chips;
 * raw text is never fed through Markdown. No file writes, no fold state to
 * re-sync — goals stay fully expanded with a hero card for the first
 * (top-level) goal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalDetailSheet(
    goalText: String,
    sourceFileName: String,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Remember per content revision; OrgParserWrapper degrades to
    // (raw content, empty nodes) on any parse failure — the raw fallback
    // branch below renders that honestly instead of guessing structure.
    val parsed = remember(goalText) { OrgParserWrapper().parseContent(goalText) }
    val preamble = parsed.first
    val nodes = parsed.second

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.88f)
                .padding(horizontal = 4.dp)
        ) {
            // Header: identity + explicit close, fixed above the scroll.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Flag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "总目标",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        text = "$sourceFileName · 只读",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭总目标",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            when {
                goalText.isBlank() -> GoalEmptyState(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                nodes.isEmpty() -> GoalRawFallback(
                    content = goalText,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                else -> {
                    nodes.firstOrNull()?.let { hero ->
                        GoalHeroCard(node = hero, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val introLines = preamble.lines()
                            .map { it.trim() }
                            .filter { it.isNotBlank() && !it.startsWith("#+") }
                        if (introLines.isNotEmpty()) {
                            item {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow
                                ) {
                                    Text(
                                        text = introLines.joinToString("\n"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                        items(nodes) { node ->
                            GoalNodeBranch(node = node)
                        }
                    }
                }
            }
        }
    }
}

/** Missing / blank goal file: guidance instead of an empty sheet body. */
@Composable
private fun GoalEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Flag,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "暂无总目标内容",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Vault 根目录的 $GOAL_FILE_HINT 内容会显示在这里",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Parser gave no headings (plain text or parse failure): show the raw lines
 * verbatim in mono — view-only text, never re-interpreted as Markdown.
 */
@Composable
private fun GoalRawFallback(content: String, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item {
            Text(
                text = "未能解析为 Org 结构，按纯文本显示",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        items(content.lines()) { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrgMono, fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Attention-catching hero for the document's first (top-level) goal. */
@Composable
private fun GoalHeroCard(node: OrgNode, modifier: Modifier = Modifier) {
    val extended = LocalExtendedColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Flag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    node.todo?.let {
                        OrgStateChip(state = it)
                    }
                    node.priority?.let {
                        PriorityChip(priority = it)
                    }
                    val descendants = remember(node) { countDescendants(node) }
                    if (descendants > 0) {
                        Text(
                            text = "$descendants 个子目标",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                        )
                    }
                }
                Text(
                    text = node.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (node.todo != null && orgStateIsDone(node.todo)) {
                        TextDecoration.LineThrough
                    } else {
                        null
                    },
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .semantics { heading() }
                )
            }
        }
    }
}

/** One goal branch: heading row + own body + nested children with a guide rail. */
@Composable
private fun GoalNodeBranch(node: OrgNode) {
    Column(modifier = Modifier.fillMaxWidth()) {
        GoalHeadingRow(node = node)
        GoalBody(node = node)
        if (node.children.isNotEmpty()) {
            val railColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            Column(
                modifier = Modifier
                    .padding(start = 12.dp, top = 2.dp)
                    .drawBehind {
                        drawLine(
                            color = railColor,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 2f
                        )
                    }
                    .padding(start = 10.dp)
            ) {
                node.children.forEach { child ->
                    GoalNodeBranch(node = child)
                }
            }
        }
    }
}

/** Heading line: TODO/priority chips + level-sized title + tags. */
@Composable
private fun GoalHeadingRow(node: OrgNode) {
    val todo = node.todo
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Level accent: level-1 keeps a teal star mark, deeper levels dim.
        Text(
            text = "*".repeat(node.level),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
            fontWeight = FontWeight.Bold,
            color = if (node.level == 1) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
        todo?.let { OrgStateChip(state = it) }
        node.priority?.let { PriorityChip(priority = it) }
        Text(
            text = node.title,
            fontSize = when {
                node.level <= 1 -> 18.sp
                node.level == 2 -> 16.sp
                else -> 14.sp
            },
            fontWeight = when (node.level) {
                1 -> FontWeight.Bold
                2 -> FontWeight.SemiBold
                else -> FontWeight.Medium
            },
            color = when {
                todo != null && orgStateIsCancelled(todo) ->
                    MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (todo != null && orgStateIsDone(todo)) {
                TextDecoration.LineThrough
            } else {
                null
            },
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
    }
    if (node.tags.isNotEmpty()) {
        Row(
            modifier = Modifier.padding(start = 18.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Cap the tag row so a heavily tagged heading can't take over.
            node.tags.take(4).forEach { tag -> TagChip(tag = tag) }
            if (node.tags.size > 4) {
                Text(
                    text = "+${node.tags.size - 4}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Own body of a heading: planning lines become compact date chips (deadline
 * urgency tinted via the same calendar-day countdown as the habit rows),
 * checklists render as checkbox rows, bullets as dots, drawers are dropped
 * (raw properties noise), everything else is plain body text.
 */
@Composable
private fun GoalBody(node: OrgNode) {
    val lines = remember(node.content) { structureBodyLines(node.content) }
    if (lines.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 2.dp)
    ) {
        lines.forEach { line ->
            when (line) {
                is BodyLine.Planning -> PlanningDateChip(line = line)
                is BodyLine.CheckItem -> CheckRow(item = line)
                is BodyLine.Bullet -> BulletRow(text = line.text)
                is BodyLine.Plain -> Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

private sealed interface BodyLine {
    data class Planning(val keyword: String, val stamp: String, val date: LocalDate?) : BodyLine
    data class CheckItem(val text: String, val done: Boolean) : BodyLine
    data class Bullet(val text: String) : BodyLine
    data class Plain(val text: String) : BodyLine
}

/** Strips drawers; classifies planning / checklist / bullet / plain lines. */
private fun structureBodyLines(content: String): List<BodyLine> {
    val result = mutableListOf<BodyLine>()
    var inDrawer = false
    content.lines().forEach { raw ->
        val trimmed = raw.trim()
        when {
            inDrawer -> if (trimmed == ":END:") inDrawer = false
            trimmed.startsWith(":") && trimmed.endsWith(":") && trimmed.length > 2 ->
                inDrawer = true // :PROPERTIES:, :LOGBOOK:, any custom drawer
            trimmed.isEmpty() -> Unit
            PLANNING_LINE_REGEX.containsMatchIn(trimmed) -> {
                val keyword = trimmed.substringBefore(":").trim().uppercase(Locale.US)
                val stamp = Regex("<([^>]+)>").find(trimmed)?.groupValues?.get(1).orEmpty()
                result += BodyLine.Planning(
                    keyword = keyword.take(1), // S / D / C
                    stamp = stamp,
                    date = stamp.substringBefore(" ").take(10)
                        .let { runCatching { LocalDate.parse(it, GOAL_DATE_FORMAT) }.getOrNull() }
                )
            }
            CHECKLIST_REGEX.containsMatchIn(trimmed) -> {
                val match = CHECKLIST_REGEX.find(trimmed)!!
                result += BodyLine.CheckItem(
                    text = match.groupValues[2],
                    done = match.groupValues[1].equals("X", ignoreCase = true)
                )
            }
            trimmed.startsWith("- ") || trimmed.startsWith("+ ") ->
                result += BodyLine.Bullet(text = trimmed.substring(2).trim())
            else -> result += BodyLine.Plain(text = trimmed)
        }
    }
    return result
}

/** SCHEDULED / DEADLINE / CLOSED line → "S 2026-10-04" mono chip. */
@Composable
private fun PlanningDateChip(line: BodyLine.Planning) {
    val extended = LocalExtendedColors.current
    val today = remember { LocalDate.now() }
    // Real DEADLINE stamps share the habit chip's calendar-day urgency colors.
    val countdown = remember(line.date, today) {
        if (line.keyword == "D" && line.date != null) habitDeadlineCountdown(line.date, today) else null
    }
    val (container, content) = when {
        countdown == null ->
            MaterialTheme.colorScheme.surfaceContainerLow to MaterialTheme.colorScheme.onSurfaceVariant
        countdown.days in 1..7 ->
            extended.warningContainer to extended.warning
        countdown.proximity != HabitDeadlineProximity.UPCOMING ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
        else ->
            MaterialTheme.colorScheme.surfaceContainerLow to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = "${line.keyword} ${line.stamp.substringBefore(" ").ifBlank { line.stamp }}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun CheckRow(item: BodyLine.CheckItem) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val checkedColor = if (item.done) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (item.done) checkedColor else Color.Transparent,
                    RoundedCornerShape(4.dp)
                )
                .then(
                    if (!item.done) {
                        Modifier.border(
                            width = 1.5.dp,
                            color = checkedColor,
                            shape = RoundedCornerShape(4.dp)
                        )
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (item.done) {
                Text(
                    text = "✓",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.done) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (item.done) TextDecoration.LineThrough else null
        )
    }
}

@Composable
private fun BulletRow(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun countDescendants(node: OrgNode): Int =
    node.children.sumOf { 1 + countDescendants(it) }

private const val GOAL_FILE_HINT = "agenda_goal.org"

private val PLANNING_LINE_REGEX = Regex("""^(SCHEDULED|DEADLINE|CLOSED):""")
private val CHECKLIST_REGEX = Regex("""^[-+]\s+\[([ Xx])]\s*(.*)$""")
private val GOAL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
