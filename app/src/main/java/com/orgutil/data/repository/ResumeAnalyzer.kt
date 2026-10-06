package com.orgutil.data.repository

import com.orgutil.data.database.entity.ChatMessageEntity

/** One tool whose execution state is unknown after an interruption. */
data class ResumeIssue(val toolName: String, val argsDigest: String)

data class ResumeReport(
    val lastUserPrompt: String?,
    val pendingApprovals: Int,
    val unknownEffectTools: List<ResumeIssue>,
    val hasAnyUserMessage: Boolean
)

/**
 * Classifies the tail of a transcript after a process death or stop, so the
 * UI can offer an honest resume: unresolved approvals are counted (they get
 * voided before resume), and approved calls with NO recorded result are
 * listed as unknown-effect - they were in flight when the run ended and may
 * or may not have mutated notes/git (FM-R3). Never invents outcomes.
 */
object ResumeAnalyzer {

    fun analyze(messages: List<ChatMessageEntity>): ResumeReport {
        var lastPrompt: String? = null
        var pending = 0
        val unknown = mutableListOf<ResumeIssue>()

        for (message in messages) {
            when (message.role) {
                "user" -> lastPrompt = ChatMessageContextCodec.decode(message.content).prompt
                "tool" -> when (message.approvalState) {
                    "PENDING" -> pending++
                    "APPROVED" -> if (message.content.isBlank() && message.toolResultSummary == null) {
                        unknown.add(ResumeIssue(message.toolName ?: "?", message.toolArgsJson ?: ""))
                    }
                }
            }
        }
        return ResumeReport(
            lastUserPrompt = lastPrompt,
            pendingApprovals = pending,
            unknownEffectTools = unknown,
            hasAnyUserMessage = lastPrompt != null
        )
    }
}
