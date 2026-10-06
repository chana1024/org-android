package com.orgutil.data.agent.tools

import com.orgutil.data.repository.OrgDestinationCreate
import com.orgutil.data.repository.OrgDestinationUpdate
import com.orgutil.data.repository.OrgIntegrationReject
import com.orgutil.data.repository.OrgIntegrationRequest
import com.orgutil.data.repository.OrgIntegrationService
import com.orgutil.data.repository.SkillRunStore
import com.orgutil.domain.chat.RiskLevel
import com.orgutil.domain.chat.ToolArgumentException
import com.orgutil.domain.chat.ToolExecutionContext
import com.orgutil.domain.chat.ToolPolicy
import com.orgutil.domain.chat.ToolResult
import com.orgutil.domain.chat.skills.OrgIntegrateSkill
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * The native /org integration capability. ONE call coordinates every
 * destination write and the verified cleanup of this run's trusted source
 * items - the model never deletes source files or rewrites sources by
 * guessed offsets itself.
 *
 * Authorization is per-run and derived from the persisted transcript via
 * [SkillRunStore]: only the reference ids attached to the session's CURRENT
 * /org invocation (not superseded by a later user message) are accepted,
 * and nothing else. Outside a /org run the tool refuses.
 *
 * Safety mirrors OrgArchiveDoneTool: risk HIGH, session grants disabled -
 * every call waits on an approval card in APPROVAL mode; AUTO mode runs it
 * immediately under the same structural guarantees (path confinement,
 * stale-hash rejection, journal + backup + rollback).
 */
class OrgIntegrateTool @Inject constructor(
    private val integrationService: OrgIntegrationService,
    private val skillRunStore: SkillRunStore
) : BaseAgentTool() {

    override val name = "org_integrate"
    override val description =
        "/org skill finalizer: writes the destination note edits/creations AND cleans the selected " +
            "source Agenda items in one coordinated, journaled step. Only the reference ids attached " +
            "to this session's current /org run are accepted. Existing destinations must be sent with " +
            "the sha256 from your last org_read_file of that file (stale contents are rejected); " +
            "same-file destinations must already exclude the original occurrence. Destinations are " +
            "written and verified first; originals are removed only afterwards. Every mutation is " +
            "journaled before it is attempted and backed up first; on failure recovery is conservative " +
            "(concurrent edits are never overwritten) and the result states exactly which sources were " +
            "verified cleaned, possibly cleaned or retained. Do not use outside a /org run."
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("reference_ids", buildJsonObject {
                    put("type", "array")
                    put("description", "Trusted reference ids from the /org message to integrate and clean")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("creates", buildJsonObject {
                    put("type", "array")
                    put("description", "New notes to create: [{path, content}] with complete file content")
                    put("items", buildJsonObject { put("type", "object") })
                })
                put("updates", buildJsonObject {
                    put("type", "array")
                    put("description", "Existing notes to replace: [{path, content, base_sha256}]")
                    put("items", buildJsonObject { put("type", "object") })
                })
                put("note", buildJsonObject {
                    put("type", "string")
                    put("description", "One short sentence about what was integrated where")
                })
            }
        )
        put(
            "required",
            JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("reference_ids"), kotlinx.serialization.json.JsonPrimitive("note")))
        )
    }
    override val policy = ToolPolicy(risk = RiskLevel.HIGH, sessionGrantAllowed = false)

    override fun describeArgs(args: JsonObject): String {
        val ids = args["reference_ids"]?.jsonArray?.size ?: 0
        val creates = args["creates"]?.jsonArray?.size ?: 0
        val updates = args["updates"]?.jsonArray?.size ?: 0
        return "integrate $ids item(s): create $creates, update $updates"
    }

    /** Plain entry point has no session context, so it can never authorize. */
    override suspend fun execute(args: JsonObject): ToolResult =
        ToolResult.Error("org_integrate requires the owning chat session; it is only callable from a run.")

    override suspend fun execute(args: JsonObject, context: ToolExecutionContext): ToolResult {
        if (context.sessionId.isBlank()) {
            return ToolResult.Error("org_integrate requires a session context.")
        }
        val selection = skillRunStore.activeOrgIntegration(context.sessionId)
            ?: return ToolResult.Error(
                "No active /org run in this session. org_integrate only works on the trusted " +
                    "references of a /org invocation that has not been superseded by a later message."
            )
        if (selection.skillId != OrgIntegrateSkill.ID) {
            return ToolResult.Error("This tool only serves the /org skill.")
        }

        val request = try {
            parseRequest(context.sessionId, selection.sources, args)
        } catch (e: ToolArgumentException) {
            return ToolResult.Error("Invalid arguments: ${e.message}")
        }

        return try {
            val outcome = integrationService.integrate(request)
            ToolResult.Ok(
                summaryForModel = outcome.message + " Journal id: ${outcome.journalId}. " +
                    retainedReport(outcome),
                affectedPaths = outcome.createdPaths + outcome.updatedPaths +
                    outcome.cleanedSources.map { it.relativePath }.distinct(),
                bytesWritten = 0
            )
        } catch (e: OrgIntegrationReject) {
            ToolResult.Error(
                "Integration rejected, sources retained: ${e.message}",
                affectedPaths = request.referenceIds.mapNotNull { id ->
                    selection.sources.find { it.referenceId == id }?.relativePath
                }.distinct()
            )
        } catch (e: Exception) {
            ToolResult.Error("Integration failed: ${e.message}")
        }
    }

    private fun parseRequest(
        sessionId: String,
        trustedSources: List<com.orgutil.domain.chat.skills.OrgSourceSnapshot>,
        args: JsonObject
    ): OrgIntegrationRequest {
        val ids = args["reference_ids"]?.jsonArray
            ?: throw ToolArgumentException("Missing argument 'reference_ids'")
        val referenceIds = ids.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
        if (referenceIds.isEmpty()) throw ToolArgumentException("'reference_ids' must be a non-empty array")

        val creates = args["creates"]?.let { parseCreates(it.jsonArray) } ?: emptyList()
        val updates = args["updates"]?.let { parseUpdates(it.jsonArray) } ?: emptyList()
        return OrgIntegrationRequest(
            sessionId = sessionId,
            trustedSources = trustedSources,
            referenceIds = referenceIds,
            creates = creates,
            updates = updates
        )
    }

    private fun parseCreates(array: JsonArray): List<OrgDestinationCreate> = array.map { element ->
        val obj = element.jsonObject
        OrgDestinationCreate(
            relativePath = obj.stringOf("path"),
            content = obj.stringOf("content")
        )
    }

    private fun parseUpdates(array: JsonArray): List<OrgDestinationUpdate> = array.map { element ->
        val obj = element.jsonObject
        OrgDestinationUpdate(
            relativePath = obj.stringOf("path"),
            content = obj.stringOf("content"),
            baseSha256 = obj.stringOf("base_sha256")
        )
    }

    private fun JsonObject.stringOf(field: String): String =
        this[field]?.jsonPrimitive?.content
            ?: throw ToolArgumentException("Destination entry is missing '$field'")

    private fun retainedReport(outcome: com.orgutil.data.repository.OrgIntegrationOutcome): String {
        val retained = outcome.cleanedSources.filterNot { it.cleaned }
        return if (retained.isEmpty()) "" else {
            "Retained (NOT cleaned): " + retained.joinToString("; ") { "${it.relativePath} - ${it.reason}" }
        }
    }
}
