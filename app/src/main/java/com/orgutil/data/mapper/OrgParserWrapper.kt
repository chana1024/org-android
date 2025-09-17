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

    fun parseContent(content: String): Pair<String, List<OrgNode>> {
        return try {
            // Extract preamble (content before first header)
            val preamble = extractPreamble(content)
            
            // Create parser with input content
            val builder = OrgParser.Builder()
            builder.setInput(content)
            
            // Optional: Configure TODO keywords
            builder.setTodoKeywords(arrayOf("TODO", "IN-PROGRESS", "WAITING"))
            builder.setDoneKeywords(arrayOf("DONE", "CANCELLED"))
            
            val parser = builder.build()
            val parsedFile: OrgParsedFile = parser.parse()
            
            // Build hierarchical structure
            val nodes = buildHierarchicalStructure(parsedFile.headsInList)
            
            Pair(preamble, nodes)
        } catch (e: IOException) {
            // If parsing fails, return empty list and raw content as preamble
            Pair(content, emptyList())
        } catch (e: Exception) {
            // If parsing fails, return empty list and raw content as preamble  
            Pair(content, emptyList())
        }
    }

    fun writeContent(preamble: String, nodes: List<OrgNode>): String {
        return try {
            val parts = mutableListOf<String>()
            
            // Add preamble if it exists
            if (preamble.isNotBlank()) {
                parts.add(preamble.trim())
            }
            
            // Add nodes content
            if (nodes.isNotEmpty()) {
                val nodesContent = nodes.joinToString("\n\n") { node ->
                    buildNodeString(node)
                }
                parts.add(nodesContent)
            }
            
            parts.joinToString("\n\n")
        } catch (e: Exception) {
            // If writing fails, return simple text representation
            val parts = mutableListOf<String>()
            if (preamble.isNotBlank()) {
                parts.add(preamble.trim())
            }
            if (nodes.isNotEmpty()) {
                val nodesContent = nodes.joinToString("\n\n") { node ->
                    buildSimpleNodeString(node)
                }
                parts.add(nodesContent)
            }
            parts.joinToString("\n\n")
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

    private fun extractPreamble(content: String): String {
        val lines = content.split("\n")
        val preambleLines = mutableListOf<String>()
        
        for (line in lines) {
            // Stop when we find the first header (line starting with *)
            if (line.trimStart().startsWith("*") && line.contains(" ")) {
                break
            }
            preambleLines.add(line)
        }
        
        return preambleLines.joinToString("\n").trim()
    }

    private fun buildHierarchicalStructure(nodesList: List<OrgNodeInList>): List<OrgNode> {
        if (nodesList.isEmpty()) return emptyList()
        
        val result = mutableListOf<OrgNode>()
        val stack = mutableListOf<Pair<OrgNode, MutableList<OrgNode>>>()
        
        for (nodeInList in nodesList) {
            val currentNode = mapOrgNodeInListToOrgNode(nodeInList)
            val currentLevel = nodeInList.level
            
            // Pop nodes from stack that are not ancestors of current node
            while (stack.isNotEmpty() && stack.last().first.level >= currentLevel) {
                val (parentNode, children) = stack.removeAt(stack.size - 1)
                val updatedParent = parentNode.copy(children = children.toList())
                
                if (stack.isNotEmpty()) {
                    stack.last().second.add(updatedParent)
                } else {
                    result.add(updatedParent)
                }
            }
            
            // If stack is empty, this is a top-level node
            if (stack.isEmpty()) {
                if (nodesList.indexOf(nodeInList) == nodesList.size - 1) {
                    // Last node, add directly
                    result.add(currentNode)
                } else {
                    // Might have children, add to stack
                    stack.add(currentNode to mutableListOf())
                }
            } else {
                // This node is a child of the last node in stack
                if (nodesList.indexOf(nodeInList) == nodesList.size - 1) {
                    // Last node, add to parent's children
                    stack.last().second.add(currentNode)
                } else {
                    // Might have children, add to stack
                    stack.add(currentNode to mutableListOf())
                }
            }
        }
        
        // Clean up remaining nodes in stack
        while (stack.isNotEmpty()) {
            val (parentNode, children) = stack.removeAt(stack.size - 1)
            val updatedParent = parentNode.copy(children = children.toList())
            
            if (stack.isNotEmpty()) {
                stack.last().second.add(updatedParent)
            } else {
                result.add(updatedParent)
            }
        }
        
        return result
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
            children = emptyList() // Will be populated by buildHierarchicalStructure
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