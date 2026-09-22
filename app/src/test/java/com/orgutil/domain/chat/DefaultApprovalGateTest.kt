package com.orgutil.domain.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultApprovalGateTest {

    private val gate = DefaultApprovalGate()
    private val policy = ToolPolicy(risk = RiskLevel.MEDIUM, sessionGrantAllowed = true)
    private val request = ToolCallRequest(id = "req-1", toolName = "org_write_file", args = kotlinx.serialization.json.JsonObject(emptyMap()))

    @Test
    fun `awaitDecision returns the user's answer`() = runTest {
        val deferred = async(UnconfinedTestDispatcher(testScheduler)) {
            gate.awaitDecision(request, policy)
        }
        testScheduler.runCurrent()
        assertEquals(1, gate.pendingCount())

        assertTrue(gate.answer("req-1", ApprovalDecision.ApproveOnce))
        assertEquals(ApprovalDecision.ApproveOnce, deferred.await())
        assertEquals(0, gate.pendingCount())
    }

    @Test
    fun `answer before registration is buffered as an early answer`() = runTest {
        // The UI can answer during the pending-event emit, before the loop
        // registers its deferred; the answer must not be lost.
        assertTrue(gate.answer("early", ApprovalDecision.ApproveSession))
        val decision = gate.awaitDecision(
            request.copy(id = "early"),
            policy
        )
        assertEquals(ApprovalDecision.ApproveSession, decision)
    }

    @Test
    fun `voidPending denies all waiters`() = runTest {
        val first = async(UnconfinedTestDispatcher(testScheduler)) {
            gate.awaitDecision(request, policy)
        }
        val second = async(UnconfinedTestDispatcher(testScheduler)) {
            gate.awaitDecision(request.copy(id = "req-2"), policy)
        }
        testScheduler.runCurrent()
        assertEquals(2, gate.pendingCount())

        gate.voidPending()

        val decisions = listOf(first.await(), second.await())
        assertTrue(decisions.all { it is ApprovalDecision.Deny })
        assertEquals(0, gate.pendingCount())
    }
}
