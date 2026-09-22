package com.orgutil.data.agent.tools

import com.orgutil.data.agent.AgentPathResolver
import com.orgutil.domain.chat.RiskLevel
import com.orgutil.domain.chat.ToolArgumentException
import com.orgutil.domain.chat.ToolPolicy
import com.orgutil.domain.chat.ToolResult
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.repository.OrgFileRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject

/**
 * Mutating tools. In APPROVAL mode each call waits on an approval card
 * (write/create may be session-granted; delete/rename never); in AUTO mode
 * they run immediately with no limits - the write path itself always keeps
 * the repository's read-back verification and index sync, which are
 * consistency flow, not approvals.
 */

class OrgWriteFileTool @Inject constructor(
    private val orgFileRepository: OrgFileRepository,
    private val pathResolver: AgentPathResolver
) : BaseAgentTool() {

    override val name = "org_write_file"
    override val description =
        "Replaces the entire content of an existing .org file. Read the file first if you need to preserve content. The write is verified by read-back before it is reported as done."
    override val parametersSchema = objectSchema(
        "path" to "Relative path of the existing file",
        "content" to "The complete new file content"
    )
    override val policy = ToolPolicy(risk = RiskLevel.MEDIUM, sessionGrantAllowed = true)

    override fun describeArgs(args: JsonObject) = optionalString(args, "path")

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = requireString(args, "path")
        val content = requireString(args, "content")
        val file = pathResolver.resolveFile(path)
        val result = orgFileRepository.writeOrgFile(
            OrgDocument(
                uri = file.uri,
                fileName = path.substringAfterLast('/'),
                content = content,
                lastModified = System.currentTimeMillis(),
                nodes = emptyList(),
                preamble = ""
            )
        )
        return result.fold(
            onSuccess = {
                ToolResult.Ok(
                    summaryForModel = "Wrote $path (${content.length} chars, verified by read-back).",
                    affectedPaths = listOf(path),
                    bytesWritten = content.encodeToByteArray().size.toLong()
                )
            },
            onFailure = { ToolResult.Error("Write failed: ${it.message}", affectedPaths = listOf(path)) }
        )
    }
}

class OrgCreateFileTool @Inject constructor(
    private val orgFileRepository: OrgFileRepository,
    private val pathResolver: AgentPathResolver
) : BaseAgentTool() {

    override val name = "org_create_file"
    override val description =
        "Creates a new .org file at the given relative path (parent directories must already exist; .org suffix is added automatically if missing)."
    override val parametersSchema = objectSchema(
        "path" to "Relative path for the new file",
        "content" to "Initial content"
    )
    override val policy = ToolPolicy(risk = RiskLevel.MEDIUM, sessionGrantAllowed = true)

    override fun describeArgs(args: JsonObject) = optionalString(args, "path")

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = requireString(args, "path")
        val content = requireString(args, "content")
        // resolveParentForCreation validates the relative path (traversal,
        // hidden/git/favorites names) and normalizes the final name so the
        // .org suffix may be omitted, as documented above.
        val (parent, finalName) = pathResolver.resolveParentForCreation(path)
        if (parent.findFile(finalName) != null) {
            return ToolResult.Error("File already exists: $path", affectedPaths = listOf(path))
        }
        val created = parent.createFile("text/org", finalName)
            ?: return ToolResult.Error("Provider refused to create $path", affectedPaths = listOf(path))
        // Route through writeOrgFile so read-back verification and index sync run.
        val result = orgFileRepository.writeOrgFile(
            OrgDocument(
                uri = created.uri,
                fileName = finalName,
                content = content,
                lastModified = System.currentTimeMillis(),
                nodes = emptyList(),
                preamble = ""
            )
        )
        return result.fold(
            onSuccess = {
                ToolResult.Ok(
                    summaryForModel = "Created $path (${content.length} chars).",
                    affectedPaths = listOf(path),
                    bytesWritten = content.encodeToByteArray().size.toLong()
                )
            },
            onFailure = { ToolResult.Error("Create failed: ${it.message}", affectedPaths = listOf(path)) }
        )
    }
}

class OrgDeleteFileTool @Inject constructor(
    private val orgFileRepository: OrgFileRepository,
    private val pathResolver: AgentPathResolver
) : BaseAgentTool() {

    override val name = "org_delete_file"
    override val description =
        "PERMANENTLY deletes a .org file. There is no trash can and no undo inside the app; recovery is only possible from a prior git commit. Confirm the exact path with the user unless running in full-auto mode."
    override val parametersSchema = objectSchema("path" to "Relative path of the file to delete")
    override val policy = ToolPolicy(risk = RiskLevel.HIGH, sessionGrantAllowed = false)

    override fun describeArgs(args: JsonObject) = optionalString(args, "path")

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = requireString(args, "path")
        val file = pathResolver.resolveFile(path)
        return orgFileRepository.deleteOrgFile(file.uri).fold(
            onSuccess = {
                ToolResult.Ok(
                    summaryForModel = "Permanently deleted $path (no undo; recoverable only from git history if it was committed).",
                    affectedPaths = listOf(path)
                )
            },
            onFailure = { ToolResult.Error("Delete failed: ${it.message}", affectedPaths = listOf(path)) }
        )
    }
}

class OrgRenameFileTool @Inject constructor(
    private val orgFileRepository: OrgFileRepository,
    private val pathResolver: AgentPathResolver
) : BaseAgentTool() {

    override val name = "org_rename_file"
    override val description =
        "Renames a .org file in place (same directory). Search index entries are updated for the old and new names."
    override val parametersSchema = objectSchema(
        "path" to "Relative path of the existing file",
        "newName" to "New file name (with .org extension), no directory part"
    )
    override val policy = ToolPolicy(risk = RiskLevel.HIGH, sessionGrantAllowed = false)

    override fun describeArgs(args: JsonObject) =
        "${optionalString(args, "path")} -> ${optionalString(args, "newName")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = requireString(args, "path")
        val newName = requireString(args, "newName")
        // Structural name validation, identical in APPROVAL and AUTO modes
        // (not an approval): bare name, org extension, no hidden/git/favorites.
        try {
            pathResolver.validateFileName(newName)
        } catch (e: ToolArgumentException) {
            return ToolResult.Error(e.message ?: "Invalid new file name")
        }
        val file = pathResolver.resolveFile(path)
        return orgFileRepository.renameOrgFile(file.uri, newName).fold(
            onSuccess = { newUri ->
                val newPath = path.substringBeforeLast('/') + "/" + newName
                ToolResult.Ok(
                    summaryForModel = "Renamed $path to $newName. New URI: $newUri",
                    affectedPaths = listOf(path, newPath),
                    bytesWritten = 0
                )
            },
            onFailure = { ToolResult.Error("Rename failed: ${it.message}", affectedPaths = listOf(path)) }
        )
    }
}
