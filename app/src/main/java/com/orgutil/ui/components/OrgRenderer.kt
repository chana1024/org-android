package com.orgutil.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orgutil.domain.model.OrgNode
import com.orgutil.ui.theme.OrgMono

@Composable
fun OrgRenderer(
    nodes: List<OrgNode>,
    modifier: Modifier = Modifier,
    globalToggleState: Boolean? = null,
    preamble: String = ""
) {
    val foldStates = remember {
        val allIds = nodes.flatMap { getAllNodeIds(it) }
        // Initialize with all headers folded by default
        // All nodes start folded (hidden) when first opening org file
        mutableStateOf(allIds.associateWith { nodeId ->
            true // All headers start folded by default
        }.toMutableMap())
    }

    // React to global toggle changes
    LaunchedEffect(globalToggleState) {
        globalToggleState?.let { shouldFold ->
            val allIds = nodes.flatMap { getAllNodeIds(it) }
            val newFoldStates = allIds.associateWith { shouldFold }.toMutableMap()
            foldStates.value = newFoldStates
        }
    }

    fun onToggleFold(node: OrgNode) {
        val nodeId = "${node.level}-${node.title}"
        val isFolded = foldStates.value[nodeId] ?: true // Default: all headers start folded
        val newFoldStates = foldStates.value.toMutableMap()
        newFoldStates[nodeId] = !isFolded

        // If we are folding, fold all children recursively
        if (!isFolded) { // Folding
            val childIds = getAllChildIds(node)
            childIds.forEach { childId ->
                newFoldStates[childId] = true
            }
        }

        foldStates.value = newFoldStates
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Display preamble content first if it exists
        if (preamble.isNotBlank()) {
            item {
                OrgPreamble(
                    content = preamble,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }
        
        items(nodes) { node ->
            OrgNodeItem(
                node = node,
                foldStates = foldStates.value,
                onToggleFold = ::onToggleFold
            )
        }
    }
}

@Composable
private fun OrgPreamble(
    content: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Document Information",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            val contentLines = content.split("\n")
                .filter { it.isNotBlank() }
            
            contentLines.forEach { line ->
                when {
                    line.startsWith("#+TITLE:") -> {
                        val title = line.removePrefix("#+TITLE:").trim()
                        if (title.isNotBlank()) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                    line.startsWith("#+AUTHOR:") -> {
                        val author = line.removePrefix("#+AUTHOR:").trim()
                        if (author.isNotBlank()) {
                            Text(
                                text = "Author: $author",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                    line.startsWith("#+DATE:") -> {
                        val date = line.removePrefix("#+DATE:").trim()
                        if (date.isNotBlank()) {
                            Text(
                                text = "Date: $date",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                    line.startsWith("#+") -> {
                        // Other org directives
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                    else -> {
                        // Regular preamble content
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun getAllChildIds(node: OrgNode): List<String> {
    return node.children.flatMap { child ->
        val childId = "${child.level}-${child.title}"
        listOf(childId) + getAllChildIds(child)
    }
}

// Helper function to get all node IDs for fold state management
private fun getAllNodeIds(node: OrgNode): List<String> {
    val nodeId = "${node.level}-${node.title}"
    return listOf(nodeId) + node.children.flatMap { getAllNodeIds(it) }
}

@Composable
private fun OrgNodeItem(
    node: OrgNode,
    foldStates: Map<String, Boolean>,
    onToggleFold: (OrgNode) -> Unit,
    modifier: Modifier = Modifier
) {
    val nodeId = "${node.level}-${node.title}"
    val isFolded = foldStates[nodeId] ?: true // Default: all headers start folded

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Headline with level, todo, priority, title, and tags
        OrgHeadline(
            node = node,
            isFolded = isFolded,
            hasChildren = node.children.isNotEmpty(),
            hasContent = node.content.isNotBlank(),
            onToggleFold = { onToggleFold(node) }
        )

        // Only show content and children if not folded
        if (!isFolded) {
            // Header content (appears directly under the headline)
            if (node.content.isNotBlank()) {
                OrgContent(
                    content = node.content,
                    level = node.level,
                    modifier = Modifier.padding(
                        start = ((node.level - 1) * 16 + 24).dp, // Align with headline text
                        top = 4.dp,
                        bottom = if (node.children.isNotEmpty()) 8.dp else 4.dp
                    )
                )
            }

            // Children nodes (recursive)
            if (node.children.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, top = if (node.content.isNotBlank()) 0.dp else 8.dp)
                ) {
                    node.children.forEach { child ->
                        OrgNodeItem(
                            node = child,
                            foldStates = foldStates,
                            onToggleFold = onToggleFold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OrgHeadline(
    node: OrgNode,
    isFolded: Boolean,
    hasChildren: Boolean,
    hasContent: Boolean,
    onToggleFold: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = hasChildren || hasContent) { onToggleFold() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: Fold indicator, Level indicator, TODO state, priority, and title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Fold indicator (only show if has children or content)
            if (hasChildren || hasContent) {
                Icon(
                    imageVector = if (isFolded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = if (isFolded) "Expand" else "Collapse",
                    modifier = Modifier
                        .size(20.dp)
                        .padding(end = 4.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Add spacing to align with nodes that have fold indicators
                Spacer(modifier = Modifier.width(24.dp))
            }
            
            // Level indicator — draft renders raw org stars as mono text
            Text(
                text = "*".repeat(node.level),
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = OrgMono),
                fontWeight = FontWeight.Bold,
                color = getStarColor(node.level),
                modifier = Modifier.padding(end = 6.dp)
            )
            
            // TODO state
            if (!node.todo.isNullOrBlank()) {
                OrgStateChip(
                    state = node.todo,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // Priority
            if (!node.priority.isNullOrBlank()) {
                PriorityChip(
                    priority = node.priority,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // Title — DONE items strike through, cancelled items dim
            val todo = node.todo
            Text(
                text = node.title,
                fontSize = getHeadlineFontSize(node.level),
                fontWeight = getHeadlineFontWeight(node.level),
                color = if (todo != null && orgStateIsCancelled(todo)) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textDecoration = if (todo != null && orgStateIsDone(todo)) {
                    TextDecoration.LineThrough
                } else {
                    null
                }
            )
        }
        
        // Right side: planning chip + tags (draft)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PlanningChip(node = node)
            if (node.tags.isNotEmpty()) {
                TagsRow(tags = node.tags)
            }
        }
    }
}

/**
 * Draft right-aligned mono planning chip: scans the node's own content for
 * a SCHEDULED/DEADLINE line ("S: 2026-10-03" / "D: 2026-10-01" on error
 * tint). Nothing renders when the headline carries no planning line.
 */
@Composable
private fun PlanningChip(node: OrgNode) {
    val planning = remember(node.content) {
        val line = node.content.lines().firstOrNull {
            val t = it.trimStart()
            t.startsWith("SCHEDULED:") || t.startsWith("DEADLINE:")
        } ?: return@remember null
        val prefix = if (line.contains("DEADLINE")) "D" else "S"
        Regex("<([^>]+)>").find(line)?.groupValues?.get(1)?.let { "$prefix: $it" }
    } ?: return

    val urgent = planning.startsWith("D")
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (urgent) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Text(
            text = planning,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
            color = if (urgent) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun TagsRow(
    tags: List<String>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tags.forEach { tag ->
            TagChip(tag = tag)
        }
    }
}

@Composable
private fun OrgContent(
    content: String,
    level: Int,
    modifier: Modifier = Modifier
) {
    // Enhanced content rendering - be more forgiving with whitespace
    val contentLines = content.split("\n")
        .map { it.trim() } // Trim each line
        .filter { it.isNotEmpty() } // Filter empty lines
    
    if (contentLines.isEmpty() && content.isBlank()) return
    
    // If we have content but no visible lines, show raw content for debugging
    val displayContent = if (contentLines.isNotEmpty()) {
        contentLines
    } else {
        listOf("Debug: Raw content length ${content.length}")
    }
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Collapse :PROPERTIES:…:END: drawers into one expandable row
            val rendered = mutableListOf<Any>() // String or List<String>
            var drawer: MutableList<String>? = null
            displayContent.forEach { line ->
                when {
                    drawer == null && line.trim() == ":PROPERTIES:" ->
                        drawer = mutableListOf(line)
                    drawer != null -> {
                        drawer!!.add(line)
                        if (line.trim() == ":END:") {
                            rendered.add(drawer!!)
                            drawer = null
                        }
                    }
                    else -> rendered.add(line)
                }
            }
            drawer?.let { rendered.add(it) } // unterminated drawer

            rendered.forEach { item ->
                when (item) {
                    is List<*> -> PropertiesDrawer(lines = item.filterIsInstance<String>())
                    is String -> when {
                        item.startsWith("- ") || item.startsWith("+ ") -> {
                            BulletItem(text = item.removePrefix("- ").removePrefix("+ "))
                        }
                        item.matches(Regex("\\d+\\.\\s.*")) -> {
                            NumberedItem(text = item)
                        }
                        item.startsWith("#+") -> {
                            OrgDirective(text = item)
                        }
                        else -> {
                            Text(
                                text = item,
                                modifier = Modifier.padding(vertical = 2.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Collapsed drawer chip "▸ :PROPERTIES: (N entries)"; expands inline. */
@Composable
private fun PropertiesDrawer(lines: List<String>) {
    var expanded by remember(lines) { mutableStateOf(false) }
    Column {
        Surface(
            onClick = { expanded = !expanded },
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = ":PROPERTIES: (${lines.size - 2} entries)",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .padding(start = 8.dp, top = 4.dp)
                    .drawBehind {
                        drawLine(
                            color = androidx.compose.ui.graphics
                                .Color(0xFFB0B2B0).copy(alpha = 0.5f),
                            start = androidx.compose.ui.geometry.Offset(0f, 0f),
                            end = androidx.compose.ui.geometry.Offset(0f, size.height),
                            strokeWidth = 2f
                        )
                    }
                    .padding(start = 8.dp)
            ) {
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BulletItem(
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 1.dp),
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NumberedItem(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier.padding(vertical = 1.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun OrgDirective(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

// Helper functions for styling

/** Level stars stay neutral per the flat design; only level 1 gets teal. */
@Composable
private fun getStarColor(level: Int): Color {
    return if (level == 1) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
}

private fun getHeadlineFontSize(level: Int) = when (level) {
    1 -> 24.sp
    2 -> 20.sp
    3 -> 18.sp
    4 -> 16.sp
    else -> 14.sp
}

private fun getHeadlineFontWeight(level: Int) = when (level) {
    1, 2 -> FontWeight.Bold
    3 -> FontWeight.SemiBold
    else -> FontWeight.Medium
}