package com.orgutil.data.agent.tools

import com.orgutil.domain.chat.RiskLevel
import com.orgutil.domain.chat.ToolPolicy
import com.orgutil.domain.chat.ToolResult
import com.orgutil.domain.repository.GitSyncRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject

/**
 * Triggers the app's fixed sync pipeline (auto commit -> union pull ->
 * push-if-ahead). The agent never touches git internals. Risk HIGH: pushing
 * publishes to the remote, so APPROVAL mode always asks (no session grant);
 * AUTO mode runs it like any other registered tool.
 */
class GitSyncTool @Inject constructor(
    private val gitSyncRepository: GitSyncRepository
) : BaseAgentTool() {

    override val name = "git_sync"
    override val description =
        "Runs the app's git sync: commits local changes, merges remote (union strategy on conflicts), pushes if ahead. The commit/push messages and strategy are fixed by the app."
    override val parametersSchema = buildJsonObject {}
    override val policy = ToolPolicy(risk = RiskLevel.HIGH, sessionGrantAllowed = false)

    override fun describeArgs(args: JsonObject) = ""

    override suspend fun execute(args: JsonObject): ToolResult {
        return gitSyncRepository.runSync().fold(
            onSuccess = { outcome ->
                ToolResult.Ok(
                    summaryForModel = "git_sync finished: ${outcome.summary}",
                    affectedPaths = emptyList()
                )
            },
            onFailure = { ToolResult.Error("git_sync failed: ${it.message}") }
        )
    }
}
