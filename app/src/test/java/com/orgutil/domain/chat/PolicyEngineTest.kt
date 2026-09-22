package com.orgutil.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the mode/policy decision table:
 * - AUTO short-circuits EVERY tool to RUN_NOW regardless of risk or grants
 *   (that is the definition of the mode - no approvals, no limits, no
 *   downgrade path);
 * - APPROVAL: LOW auto-runs, MEDIUM waits unless session-granted,
 *   HIGH always waits (no session grant for git_sync/delete/rename).
 */
class PolicyEngineTest {

    private fun policy(risk: RiskLevel, sessionGrantAllowed: Boolean = false) =
        ToolPolicy(risk = risk, sessionGrantAllowed = sessionGrantAllowed)

    @Test
    fun `auto mode short-circuits every risk level`() {
        RiskLevel.entries.forEach { risk ->
            val (outcome, source) = PolicyEngine.decide(AgentMode.AUTO, policy(risk), hasSessionGrant = false)
            assertEquals(PolicyOutcome.RUN_NOW, outcome)
            assertEquals(DecisionSource.AUTO_POLICY, source)
        }
    }

    @Test
    fun `auto mode ignores session grants`() {
        val (outcome, source) = PolicyEngine.decide(
            AgentMode.AUTO,
            policy(RiskLevel.MEDIUM, sessionGrantAllowed = true),
            hasSessionGrant = true
        )
        assertEquals(PolicyOutcome.RUN_NOW, outcome)
        assertEquals(DecisionSource.AUTO_POLICY, source)
    }

    @Test
    fun `approval mode auto-runs low risk`() {
        val (outcome, source) = PolicyEngine.decide(AgentMode.APPROVAL, policy(RiskLevel.LOW), false)
        assertEquals(PolicyOutcome.RUN_NOW, outcome)
        assertEquals(DecisionSource.AUTO_POLICY, source)
    }

    @Test
    fun `approval mode waits for medium risk without grant`() {
        val (outcome, source) = PolicyEngine.decide(
            AgentMode.APPROVAL,
            policy(RiskLevel.MEDIUM, sessionGrantAllowed = true),
            hasSessionGrant = false
        )
        assertEquals(PolicyOutcome.PENDING_APPROVAL, outcome)
        assertEquals(DecisionSource.USER_ONCE, source)
    }

    @Test
    fun `approval mode runs medium risk on session grant`() {
        val (outcome, source) = PolicyEngine.decide(
            AgentMode.APPROVAL,
            policy(RiskLevel.MEDIUM, sessionGrantAllowed = true),
            hasSessionGrant = true
        )
        assertEquals(PolicyOutcome.RUN_NOW, outcome)
        assertEquals(DecisionSource.USER_SESSION, source)
    }

    @Test
    fun `approval mode always waits for high risk even with grants`() {
        val (outcome, source) = PolicyEngine.decide(
            AgentMode.APPROVAL,
            policy(RiskLevel.HIGH, sessionGrantAllowed = false),
            hasSessionGrant = true
        )
        assertEquals(PolicyOutcome.PENDING_APPROVAL, outcome)
        assertEquals(DecisionSource.USER_ONCE, source)
    }
}
