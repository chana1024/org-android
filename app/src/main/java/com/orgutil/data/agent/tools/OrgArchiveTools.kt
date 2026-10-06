package com.orgutil.data.agent.tools

import com.orgutil.data.repository.GtdArchivePlan
import com.orgutil.data.repository.GtdArchiveService
import com.orgutil.data.repository.SkillRunStore
import com.orgutil.domain.chat.RiskLevel
import com.orgutil.domain.chat.ToolExecutionContext
import com.orgutil.domain.chat.ToolPolicy
import com.orgutil.domain.chat.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * Agent-facing "archive DONE": runs the exact same [GtdArchiveService]
 * core as the Agenda action - same selector (exact TODO state DONE in the
 * active GTD files only), same destination (gtd/archive.org), same
 * plan/verify/backup/rollback path, same idempotency (archive.org is never
 * a source, outermost-subtree-only) and the same bounded item cap.
 *
 * Safety mirrors OrgDeleteFileTool: risk HIGH and session grants disabled,
 * so in APPROVAL mode every single call waits on an approval card (HIGH
 * risk never consults the session-grant cache) and the AUTO mode banner
 * already warns that notes are modified without per-call confirmation.
 */
class OrgArchiveDoneTool @Inject constructor(
    private val archiveService: GtdArchiveService,
    private val skillRunStore: SkillRunStore
) : BaseAgentTool() {

    override val name = "org_archive_done"
    override val description =
        "Moves every task in the active GTD files whose TODO state is exactly DONE into " +
            "gtd/archive.org (same as the Doom GTD workflow). Subtrees move verbatim " +
            "(properties, drawers, logbooks, child tasks, planning cookies). CANCELLED and " +
            "DROPPED items are never touched. The plan is re-verified against the files " +
            "right before writing; originals are backed up to app-private storage and " +
            "restored on any failure. Re-running is safe: archived items are never rescanned."
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("max_items", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional upper bound (1-${GtdArchiveService.MAX_ITEMS}) on how many " +
                            "items to move; extra items are left in place"
                    )
                })
            }
        )
        put("required", kotlinx.serialization.json.JsonArray(emptyList()))
    }
    override val policy = ToolPolicy(risk = RiskLevel.HIGH, sessionGrantAllowed = false)

    /** Bounded digest shown on the approval card and written to the audit log. */
    override fun describeArgs(args: JsonObject): String {
        val bound = optionalString(args, "max_items").trim().toIntOrNull()
        return if (bound != null) "archive DONE (cap $bound)" else "archive all DONE"
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val bound = optionalString(args, "max_items").trim().toIntOrNull()?.takeIf { it > 0 }
        return archiveService.plan().fold(            onSuccess = { full ->
                val selected = if (bound != null) full.items.take(bound) else full.items
                if (selected.isEmpty()) {
                    return ToolResult.Ok(summaryForModel = "No DONE items to archive.")
                }
                val plan = full.copy(items = selected)
                archiveService.execute(plan).fold(
                    onSuccess = { count ->
                        val leftBehind = full.items.size - selected.size
                        val suffix = if (leftBehind > 0) {
                            " $leftBehind item(s) left in place (cap $bound)."
                        } else ""
                        ToolResult.Ok(
                            summaryForModel = "Archived $count DONE item(s) to ${plan.destinationPath} " +
                                "(verbatim subtrees; originals backed up; CANCELLED/DROPPED untouched)." + suffix,
                            affectedPaths = plan.items.map { it.fileName }.distinct() + plan.destinationPath
                        )
                    },
                    onFailure = {
                        ToolResult.Error(
                            "Archive failed: ${it.message}",
                            affectedPaths = plan.items.map { it.fileName }.distinct()
                        )
                    }
                )
            },
            onFailure = { ToolResult.Error("Archive planning failed: ${it.message}") }
        )
    }

    /** Mass source rewrites would bypass /org cleanup coordination. */
    override suspend fun execute(args: JsonObject, context: ToolExecutionContext): ToolResult {
        skillRunStore.assertOrgRunAllowsDirectEdits(context.sessionId)
        return execute(args)
    }
}
