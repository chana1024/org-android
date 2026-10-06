package com.orgutil.data.mapper

import com.orgutil.domain.agenda.OrgAgendaParser
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
            
            // Keep the viewer aligned with the GTD/PARA agenda keyword set —
            // single source of truth is OrgAgendaParser's ordered sequence.
            builder.setTodoKeywords(OrgAgendaParser.NOT_DONE_KEYWORDS.toTypedArray())
            builder.setDoneKeywords(OrgAgendaParser.DONE_KEYWORDS.toTypedArray())
            
            val parser = builder.build()
            val parsedFile: OrgParsedFile = parser.parse()

            // Build hierarchical structure
            val nodes = buildHierarchicalStructure(parsedFile.headsInList, content)

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

    private fun buildHierarchicalStructure(nodesList: List<OrgNodeInList>, content: String): List<OrgNode> {
        if (nodesList.isEmpty()) return emptyList()

        // Verified (sourceOffset, titleOffset) per heading, in document
        // order — the identity the per-heading source actions rely on.
        val identities = resolveHeadingIdentities(nodesList, content)

        val result = mutableListOf<OrgNode>()
        val stack = mutableListOf<Pair<OrgNode, MutableList<OrgNode>>>()

        for ((index, nodeInList) in nodesList.withIndex()) {
            val (sourceOffset, titleOffset) = identities[index]
            val currentNode = mapOrgNodeInListToOrgNode(nodeInList, sourceOffset, titleOffset)
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

    private fun mapOrgNodeInListToOrgNode(
        nodeInList: OrgNodeInList,
        sourceOffset: Int,
        titleOffset: Int
    ): OrgNode {
        val head = nodeInList.head
        val content = head.content ?: ""
        return OrgNode(
            level = nodeInList.level,
            title = head.title ?: "",
            content = content,
            tags = head.tags?.toList() ?: emptyList(),
            todo = head.state,
            priority = head.priority,
            children = emptyList(), // Will be populated by buildHierarchicalStructure
            sourceOffset = sourceOffset,
            titleOffset = titleOffset,
            // orgzly consumes the own :PROPERTIES: drawer and the SCHEDULED
            // planning line out of head.content (into head.properties /
            // head.scheduled), so read them from the parsed fields first;
            // the content regexes stay as fallback for anything orgzly
            // leaves unconsumed.
            isHabitStyle = head.hasHabitStyleProperty() || content.hasProperty("STYLE", "habit"),
            hasRepeatingScheduled = head.hasRepeatingScheduled() ||
                SCHEDULED_REPEATER_REGEX.containsMatchIn(content)
        )
    }

    /** STYLE=habit in orgzly's parsed own-properties map. */
    private fun OrgHead.hasHabitStyleProperty(): Boolean =
        properties?.get("STYLE")?.trim()?.equals("habit", ignoreCase = true) == true

    /** The orgzly-parsed own SCHEDULED timestamp carries a repeater. */
    private fun OrgHead.hasRepeatingScheduled(): Boolean =
        scheduled?.startTime?.hasRepeater() == true

    /**
     * Zips the raw headline scan with orgzly's flat node list (both are in
     * document order) and verifies each pair: same star count and a title
     * occurrence whose preceding tokens are exactly the parsed keyword and
     * priority. Any heading that cannot be verified keeps (-1, -1) so no
     * source-level action is ever offered for it; a count mismatch between
     * the scan and orgzly disables identity for the whole file rather than
     * risk shifting offsets onto wrong headings.
     */
    private fun resolveHeadingIdentities(
        nodesList: List<OrgNodeInList>,
        content: String
    ): List<Pair<Int, Int>> {
        val unknown = -1 to -1
        val matches = HEADLINE_REGEX.findAll(content).toList()
        if (matches.size != nodesList.size) return List(nodesList.size) { unknown }

        return nodesList.mapIndexed { index, nodeInList ->
            val match = matches[index]
            if (match.groupValues[1].length != nodeInList.level) return@mapIndexed unknown

            val head = nodeInList.head
            val title = head.title.orEmpty()
            val remainderStart = match.groups[2]?.range?.first ?: return@mapIndexed unknown
            val lineEnd = content.indexOf('\n', startIndex = remainderStart)
                .let { if (it == -1) content.length else it }
            val prefixTokens = buildList {
                head.state?.let { add(it) }
                head.priority?.let { add("[#$it]") }
            }

            // The first on-line title occurrence preceded by exactly the
            // parsed keyword/priority tokens is the real title start (guards
            // against the title being a substring of an earlier token).
            var searchFrom = remainderStart
            var resolved = unknown
            if (title.isNotEmpty()) {
                while (searchFrom < lineEnd) {
                    val at = content.indexOf(title, searchFrom)
                    if (at < 0 || at + title.length > lineEnd) break
                    val actualPrefix = content.substring(remainderStart, at).trim()
                        .split(WHITESPACE_REGEX).filter { it.isNotBlank() }
                    if (actualPrefix == prefixTokens) {
                        resolved = match.range.first to at
                        break
                    }
                    searchFrom = at + 1
                }
            }
            resolved
        }
    }

    /** Own-drawer property lookup on the heading's raw content block. */
    private fun String.hasProperty(key: String, expectedValue: String): Boolean {
        val drawer = OWN_PROPERTIES_DRAWER_REGEX.find(this)?.value ?: return false
        val value = OWN_PROPERTY_LINE_REGEX.findAll(drawer)
            .firstOrNull { it.groupValues[1].equals(key, ignoreCase = true) }
            ?.groupValues?.get(2)?.trim() ?: return false
        return value.equals(expectedValue, ignoreCase = true)
    }

    private companion object {
        /** Mirrors OrgAgendaParser.HEADLINE_REGEX — the same scan the agenda runs. */
        val HEADLINE_REGEX = Regex("""(?m)^(\*+)\s+(.+)$""")
        val WHITESPACE_REGEX = Regex("\\s+")

        /** Both drawer patterns mirror OrgAgendaParser so the viewer reads
         * exactly the regions the habit parser reads. */
        val OWN_PROPERTIES_DRAWER_REGEX = Regex(
            """(?ms)^[ \t]*:PROPERTIES:[ \t]*\r?\n(.*?)[ \t]*:END:"""
        )
        val OWN_PROPERTY_LINE_REGEX = Regex("""(?m)^[ \t]*:([A-Za-z0-9_-]+):[ \t]*(.*)$""")

        /** Own SCHEDULED line carrying a repeater, e.g. ".+1w", "++1m", "+2d/5d". */
        val SCHEDULED_REPEATER_REGEX = Regex(
            """(?m)^[ \t]*SCHEDULED:[ \t]*<[^>\n]*?(\.\+|\+\+|\+)\d+[dwmy](?:\s*/\s*\d+[dwmy])?[^>\n]*>"""
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
