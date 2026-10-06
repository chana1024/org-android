package com.orgutil.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins FM-S2 (活动会话重启漂移): the active session must come from the
 * persisted active id when it still exists; fall back to the most recently
 * updated session only when the persisted one is gone or was never set;
 * create a new one only when no session exists at all. Ordering input is
 * the session list ordered by updatedAt DESC (same ordering the DAO uses).
 */
class ActiveSessionResolverTest {

    private fun resolve(savedId: String?, orderedIds: List<String>): String? =
        ActiveSessionResolver.resolve(savedId, orderedIds)

    @Test
    fun `saved id wins when the session still exists`() {
        assertEquals(
            "s-old",
            resolve(savedId = "s-old", orderedIds = listOf("s-new", "s-old", "s-mid"))
        )
    }

    @Test
    fun `saved id deleted falls back to most recently updated session`() {
        assertEquals(
            "s-new",
            resolve(savedId = "s-deleted", orderedIds = listOf("s-new", "s-old"))
        )
    }

    @Test
    fun `no saved id uses most recently updated session`() {
        assertEquals("s-new", resolve(savedId = null, orderedIds = listOf("s-new", "s-old")))
    }

    @Test
    fun `empty database means create new`() {
        assertNull(resolve(savedId = null, orderedIds = emptyList()))
        assertNull(resolve(savedId = "s-gone", orderedIds = emptyList()))
    }
}
