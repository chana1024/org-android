package com.orgutil.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orgutil.domain.model.OrgNode

@Composable
fun OrgRenderer(
    nodes: List<OrgNode>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(nodes) { node ->
            OrgNodeItem(node = node)
        }
    }
}

@Composable
private fun OrgNodeItem(
    node: OrgNode,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Headline with level, todo, priority, title, and tags
        OrgHeadline(node = node)
        
        // Content
        if (node.content.isNotBlank()) {
            OrgContent(
                content = node.content,
                level = node.level,
                modifier = Modifier.padding(start = (node.level * 16).dp, top = 4.dp)
            )
        }
        
        // Children nodes (recursive)
        if (node.children.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            ) {
                node.children.forEach { child ->
                    OrgNodeItem(node = child)
                }
            }
        }
    }
}

@Composable
private fun OrgHeadline(
    node: OrgNode,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: Level indicator, TODO state, priority, and title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
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
    // Simple content rendering - could be enhanced with markup parsing
    val contentLines = content.split("\n")
    
    Column(modifier = modifier) {
        contentLines.forEach { line ->
            when {
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("+ ") -> {
                    // Bullet point
                    BulletItem(text = line.trimStart().removePrefix("- ").removePrefix("+ "))
                }
                line.trimStart().matches(Regex("\\d+\\.\\s.*")) -> {
                    // Numbered list
                    NumberedItem(text = line.trimStart())
                }
                line.trim().startsWith("#+") -> {
                    // Org directive (like #+TITLE:, #+AUTHOR:, etc.)
                    OrgDirective(text = line.trim())
                }
                else -> {
                    // Regular text
                    if (line.trim().isNotEmpty()) {
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