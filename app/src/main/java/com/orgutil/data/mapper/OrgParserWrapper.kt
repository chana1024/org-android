package com.orgutil.data.mapper

import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.model.OrgNode
import com.orgzly.org.OrgHead
import com.orgzly.org.parser.OrgParser
import com.orgzly.org.parser.OrgParsedFile
import com.orgzly.org.parser.OrgNodeInList
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrgParserWrapper @Inject constructor() {

    fun parseContent(content: String): List<OrgNode> {
        return try {
            // Create parser with input content
            val builder = OrgParser.Builder()
            builder.setInput(content)
            
            // Optional: Configure TODO keywords
            builder.setTodoKeywords(arrayOf("TODO", "IN-PROGRESS", "WAITING"))
            builder.setDoneKeywords(arrayOf("DONE", "CANCELLED"))
            
            val parser = builder.build()
            val parsedFile: OrgParsedFile = parser.parse()
            
            // Convert OrgNodeInList to our domain model
            parsedFile.headsInList.map { nodeInList ->
                mapOrgNodeInListToOrgNode(nodeInList)
            }
        } catch (e: IOException) {
            // If parsing fails, return empty list
            emptyList()
        } catch (e: Exception) {
            // If parsing fails, return empty list
            emptyList()
        }
    }

    fun writeContent(nodes: List<OrgNode>): String {
        return try {
            // For writing, we'll use a simple string builder approach
            // as recreating the full OrgParsedFile structure is complex
            nodes.joinToString("\n\n") { node ->
                buildNodeString(node)
            }
        } catch (e: Exception) {
            // If writing fails, return simple text representation
            nodes.joinToString("\n\n") { node ->
                buildSimpleNodeString(node)
            }
        }
    }

    private fun mapOrgNodeInListToOrgNode(nodeInList: OrgNodeInList): OrgNode {
        val head = nodeInList.head
        return OrgNode(
            level = nodeInList.level,
            title = head.title ?: "",
            content = head.content ?: "",
            tags = head.tags?.toList() ?: emptyList(),
            todo = head.state,
            priority = head.priority,
            children = emptyList() // Flatten structure for simplicity
        )
    }

    private fun buildNodeString(node: OrgNode): String {
        return buildString {
            // Add the headline with proper level
            append("*".repeat(node.level))
            append(" ")
            
            // Add TODO state if present
            if (!node.todo.isNullOrBlank()) {
                append(node.todo)
                append(" ")
            }
            
            // Add priority if present
            if (!node.priority.isNullOrBlank()) {
                append("[#${node.priority}] ")
            }
            
            // Add title
            append(node.title)
            
            // Add tags if present
            if (node.tags.isNotEmpty()) {
                append(" ")
                append(":")
                append(node.tags.joinToString(":"))
                append(":")
            }
            
            // Add content if present
            if (node.content.isNotBlank()) {
                append("\n")
                append(node.content)
            }
            
            // Add children (recursive)
            if (node.children.isNotEmpty()) {
                append("\n")
                append(node.children.joinToString("\n") { child ->
                    buildNodeString(child)
                })
            }
        }
    }

    private fun buildSimpleNodeString(node: OrgNode): String {
        return buildString {
            append("*".repeat(node.level))
            append(" ")
            if (node.todo != null) {
                append(node.todo)
                append(" ")
            }
            append(node.title)
            if (node.tags.isNotEmpty()) {
                append(" :")
                append(node.tags.joinToString(":"))
                append(":")
            }
            if (node.content.isNotBlank()) {
                append("\n")
                append(node.content)
            }
        }
    }
}