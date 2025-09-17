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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orgutil.domain.model.OrgNode

@Composable
fun OrgRenderer(
    nodes: List<OrgNode>,
    modifier: Modifier = Modifier,
    globalToggleState: Boolean? = null
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
        items(nodes) { node ->
            OrgNodeItem(
                node = node,
                foldStates = foldStates.value,
                onToggleFold = ::onToggleFold
            )
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
    onToggleFold: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = hasChildren) { onToggleFold() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: Fold indicator, Level indicator, TODO state, priority, and title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Fold indicator (only show if has children)
            if (hasChildren) {
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
            
            // Level indicator (stars)
            Text(
                text = "★".repeat(node.level),
                color = getStarColor(node.level),
                fontSize = getHeadlineFontSize(node.level),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 8.dp)
            )
            
            // TODO state
            if (!node.todo.isNullOrBlank()) {
                TodoBadge(
                    todoState = node.todo,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            
            // Priority
            if (!node.priority.isNullOrBlank()) {
                PriorityBadge(
                    priority = node.priority,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            
            // Title
            Text(
                text = node.title,
                fontSize = getHeadlineFontSize(node.level),
                fontWeight = getHeadlineFontWeight(node.level),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        
        // Right side: Tags
        if (node.tags.isNotEmpty()) {
            TagsRow(tags = node.tags)
        }
    }
}

@Composable
private fun TodoBadge(
    todoState: String,
    modifier: Modifier = Modifier
) {
    val (backgroundColor, textColor) = getTodoColors(todoState)
    
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        contentColor = textColor
    ) {
        Text(
            text = todoState,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PriorityBadge(
    priority: String,
    modifier: Modifier = Modifier
) {
    val backgroundColor = getPriorityColor(priority)
    
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = backgroundColor,
        contentColor = Color.White
    ) {
        Text(
            text = "[#$priority]",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
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
private fun TagChip(
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
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            displayContent.forEach { line ->
                when {
                    line.startsWith("- ") || line.startsWith("+ ") -> {
                        // Bullet point
                        BulletItem(text = line.removePrefix("- ").removePrefix("+ "))
                    }
                    line.matches(Regex("\\d+\\.\\s.*")) -> {
                        // Numbered list
                        NumberedItem(text = line)
                    }
                    line.startsWith("#+") -> {
                        // Org directive (like #+TITLE:, #+AUTHOR:, etc.)
                        OrgDirective(text = line)
                    }
                    else -> {
                        // Regular text
                        Text(
                            text = line,
                            modifier = Modifier.padding(vertical = 2.dp),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
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
        fontSize = 14.sp,
        lineHeight = 20.sp,
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
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

// Helper functions for styling

@Composable
private fun getStarColor(level: Int): Color {
    return when (level) {
        1 -> MaterialTheme.colorScheme.primary
        2 -> MaterialTheme.colorScheme.secondary
        3 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
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

@Composable
private fun getTodoColors(todoState: String): Pair<Color, Color> {
    return when (todoState.uppercase()) {
        "TODO" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "IN-PROGRESS", "STARTED" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "WAITING" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "DONE" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "CANCELLED", "CANCELED" -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f) to MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun getPriorityColor(priority: String): Color {
    return when (priority.uppercase()) {
        "A" -> Color(0xFFE53E3E) // Red
        "B" -> Color(0xFFED8936) // Orange
        "C" -> Color(0xFF3182CE) // Blue
        else -> MaterialTheme.colorScheme.outline
    }
}