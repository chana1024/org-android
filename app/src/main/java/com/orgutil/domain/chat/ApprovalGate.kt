package com.orgutil.domain.chat

import kotlinx.coroutines.CompletableDeferred
import javax.inject.Inject
import javax.inject.Singleton

/** What the user answered on an approval card. */
sealed class ApprovalDecision {
    object ApproveOnce : ApprovalDecision()
    object ApproveSession : ApprovalDecision()
    data class Deny(val reason: String? = null) : ApprovalDecision()
}

/**
 * Decides whether a tool call may run. In AUTO mode the gate is bypassed by
 * the loop (PolicyEngine already returned RUN_NOW); in APPROVAL mode the
 * gate suspends on a [CompletableDeferred] until the UI answers.
 */
interface ApprovalGate {
    suspend fun awaitDecision(request: ToolCallRequest, policy: ToolPolicy): ApprovalDecision

    /** Stop button semantics: void every pending approval. */
    fun voidPending()
}

@Singleton
class DefaultApprovalGate @Inject constructor() : ApprovalGate {

    private val pending = mutableMapOf<String, CompletableDeferred<ApprovalDecision>>()

    // Answers that arrived before the loop registered the deferred (the UI
    // sees the pending event during emit, before awaitDecision runs).
    private val earlyAnswers = mutableMapOf<String, ApprovalDecision>()

    override suspend fun awaitDecision(request: ToolCallRequest, policy: ToolPolicy): ApprovalDecision {
        earlyAnswers.remove(request.id)?.let { return it }
        val deferred = CompletableDeferred<ApprovalDecision>()
        synchronized(pending) { pending[request.id] = deferred }
        try {
            return deferred.await()
        } finally {
            synchronized(pending) { pending.remove(request.id) }
        }
    }

    fun answer(requestId: String, decision: ApprovalDecision): Boolean {
        val deferred = synchronized(pending) { pending.remove(requestId) }
        if (deferred != null) return deferred.complete(decision)
        synchronized(earlyAnswers) { earlyAnswers[requestId] = decision }
        return true
    }

    override fun voidPending() {
        val all = synchronized(pending) { pending.values.toList() }
        pending.clear()
        synchronized(earlyAnswers) { earlyAnswers.clear() }
        all.forEach { it.complete(ApprovalDecision.Deny(reason = "stopped")) }
    }

    fun pendingCount(): Int = synchronized(pending) { pending.size }
}

/**
 * Session-scoped "always allow" cache for MEDIUM tools (write/create).
 * Lives in memory only - it expires with the process by design.
 */
class SessionGrantStore {
    private val grants = mutableSetOf<String>()

    fun key(toolName: String, targetPath: String?): String = "$toolName:${targetPath ?: "*"}"

    fun grant(key: String) {
        grants.add(key)
    }

    fun has(key: String): Boolean = grants.contains(key)
}
