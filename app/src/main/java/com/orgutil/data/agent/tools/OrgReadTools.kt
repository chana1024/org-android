package com.orgutil.data.agent.tools

import com.orgutil.domain.chat.RiskLevel
import com.orgutil.domain.chat.ToolCallRequest
import com.orgutil.domain.chat.ToolPolicy
import com.orgutil.domain.chat.ToolResult
import com.orgutil.domain.model.OrgNode
import com.orgutil.domain.repository.OrgFileRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject

/**
 * Read-only tools (risk LOW): they run without confirmation in both modes.
 * Output truncation is context management for the LLM, not an approval limit.
 */

class OrgListFilesTool @Inject constructor(
    private val orgFileRepository: OrgFileRepository
) : BaseAgentTool() {

    override val name = "org_list_files"
    override val description =
        "Lists all .org files in the notes tree as relative paths. Use this to discover what exists before reading or writing."
    override val parametersSchema = buildJsonObject {}
    override val policy = ToolPolicy(risk = RiskLevel.LOW, sessionGrantAllowed = false)

    override fun describeArgs(args: JsonObject) = ""

    override suspend fun execute(args: JsonObject): ToolResult {
        val files = orgFileRepository.getAllOrgFiles()
        val paths = files.map { it.name }.sorted()
        val shown = paths.take(MAX_LIST)
        val suffix = if (paths.size > MAX_LIST) "\n... (${paths.size - MAX_LIST} more)" else ""
        return ToolResult.Ok(
            summaryForModel = if (paths.isEmpty()) {
                "No .org files found."
            } else {
                shown.joinToString("\n", prefix = "${paths.size} files:\n") + suffix
            }
        )
    }

    private companion object {
        const val MAX_LIST = 500
    }
}

class OrgSearchTool @Inject constructor(
    private val orgFileRepository: OrgFileRepository
) : BaseAgentTool() {

    override val name = "org_search"
    override val description =
        "Full-text search over note contents (supports Chinese substrings and English token prefixes). Returns matching files with a short preview."
    override val parametersSchema = objectSchema("query" to "Search text")
    override val policy = ToolPolicy(risk = RiskLevel.LOW, sessionGrantAllowed = false)

    override fun describeArgs(args: JsonObject) = "query=\"${optionalString(args, "query")}\""

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = requireString(args, "query")
        val results = orgFileRepository.getOrgFiles(query = query, useDatabase = true).first()
        val hits = results.mapNotNull { info ->
            info.searchPreview?.let { preview -> "${info.name}: …$preview…" }
        }
        if (hits.isEmpty()) return ToolResult.Ok(summaryForModel = "No matches for \"$query\".")
        val shown = hits.take(MAX_HITS)
        val suffix = if (hits.size > MAX_HITS) "\n... (${hits.size - MAX_HITS} more)" else ""
        return ToolResult.Ok(summaryForModel = shown.joinToString("\n") + suffix)
    }

    private companion object {
        const val MAX_HITS = 30
    }
}

class OrgReadFileTool @Inject constructor(
    private val orgFileRepository: OrgFileRepository,
    private val pathResolver: com.orgutil.data.agent.AgentPathResolver
) : BaseAgentTool() {

    override val name = "org_read_file"
    override val description =
        "Reads the full content of one .org file. Path is relative to the notes tree root."
    override val parametersSchema = objectSchema("path" to "Relative path, e.g. notes/gtd.org")
    override val policy = ToolPolicy(risk = RiskLevel.LOW, sessionGrantAllowed = false)

    override fun describeArgs(args: JsonObject) = optionalString(args, "path")

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = requireString(args, "path")
        val document = pathResolver.resolveFile(path).let { file ->
            orgFileRepository.readOrgFile(file.uri).getOrElse { error ->
                return ToolResult.Error("Read failed: ${error.message}", affectedPaths = listOf(path))
            }
        }
        val content = document.content
        return if (content.length > MAX_CONTENT_CHARS) {
            ToolResult.Ok(
                summaryForModel = content.take(MAX_CONTENT_CHARS) +
                    "\n... [truncated, ${content.length} chars total]",
                affectedPaths = listOf(path)
            )
        } else {
            ToolResult.Ok(summaryForModel = content, affectedPaths = listOf(path))
        }
    }

    private companion object {
        const val MAX_CONTENT_CHARS = 60_000
    }
}

class OrgParseOutlineTool @Inject constructor(
    private val orgFileRepository: OrgFileRepository,
    private val pathResolver: com.orgutil.data.agent.AgentPathResolver
) : BaseAgentTool() {

    override val name = "org_parse_outline"
    override val description =
        "Returns the heading outline of a .org file (levels, TODO states, tags) without the body - cheaper than reading the whole file."
    override val parametersSchema = objectSchema("path" to "Relative path, e.g. notes/gtd.org")
    override val policy = ToolPolicy(risk = RiskLevel.LOW, sessionGrantAllowed = false)

    override fun describeArgs(args: JsonObject) = optionalString(args, "path")

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = requireString(args, "path")
        val file = pathResolver.resolveFile(path)
        val document = orgFileRepository.readOrgFile(file.uri).getOrElse { error ->
            return ToolResult.Error("Read failed: ${error.message}", affectedPaths = listOf(path))
        }
        val lines = mutableListOf<String>()
        render(document.nodes, depth = 0, lines = lines)
        return if (lines.isEmpty()) {
            ToolResult.Ok(summaryForModel = "(file has no headings)", affectedPaths = listOf(path))
        } else {
            val shown = lines.take(MAX_LINES)
            val suffix = if (lines.size > MAX_LINES) "\n... (${lines.size - MAX_LINES} more)" else ""
            ToolResult.Ok(summaryForModel = shown.joinToString("\n") + suffix, affectedPaths = listOf(path))
        }
    }

    private fun render(nodes: List<OrgNode>, depth: Int, lines: MutableList<String>) {
        for (node in nodes) {
            val indent = "  ".repeat(depth)
            val todo = node.todo?.let { "$it " } ?: ""
            val tags = if (node.tags.isEmpty()) "" else " :${node.tags.joinToString(":")}:"
            lines.add("$indent- $todo${node.title}$tags")
            render(node.children, depth + 1, lines)
        }
    }

    private companion object {
        const val MAX_LINES = 300
    }
}
