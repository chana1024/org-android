package com.orgutil.domain.chat

/**
 * Agent harness domain model: modes, risk tiers, and policy outcomes.
 *
 * APPROVAL: read-only tools run automatically; write/create/delete/rename
 * and git_sync need per-call confirmation (write/create may also be granted
 * for the rest of the session).
 *
 * AUTO: no approvals, no session grants, no size/count/volume limits and no
 * downgrade path - every registered tool runs immediately. The scope of AUTO
 * is the tool registry itself (in-tree .org files only, no shell, no
 * out-of-tree paths); those limits are structural, not approvals.
 */
enum class AgentMode { APPROVAL, AUTO }

enum class RiskLevel { LOW, MEDIUM, HIGH }

enum class DecisionSource { USER_ONCE, USER_SESSION, AUTO_POLICY }

enum class ApprovalState { PENDING, APPROVED, DENIED, VOIDED }

/** Immutable per-tool policy; consulted only in APPROVAL mode. */
data class ToolPolicy(
    val risk: RiskLevel,
    /** APPROVAL mode: write/create may be granted for the rest of the session. */
    val sessionGrantAllowed: Boolean
)

enum class PolicyOutcome {
    /** Execute immediately; [DecisionSource] explains why. */
    RUN_NOW,

    /** Suspend until the user answers the approval card. */
    PENDING_APPROVAL
}

/**
 * Pure decision table for tool calls. AUTO short-circuits before risk or
 * grants are even looked at - that is the whole definition of the mode.
 */
object PolicyEngine {
    fun decide(mode: AgentMode, policy: ToolPolicy, hasSessionGrant: Boolean): Pair<PolicyOutcome, DecisionSource> {
        return when (mode) {
            AgentMode.AUTO -> PolicyOutcome.RUN_NOW to DecisionSource.AUTO_POLICY
            AgentMode.APPROVAL -> when (policy.risk) {
                RiskLevel.LOW -> PolicyOutcome.RUN_NOW to DecisionSource.AUTO_POLICY
                RiskLevel.MEDIUM ->
                    if (hasSessionGrant) PolicyOutcome.RUN_NOW to DecisionSource.USER_SESSION
                    else PolicyOutcome.PENDING_APPROVAL to DecisionSource.USER_ONCE
                RiskLevel.HIGH -> PolicyOutcome.PENDING_APPROVAL to DecisionSource.USER_ONCE
            }
        }
    }
}
