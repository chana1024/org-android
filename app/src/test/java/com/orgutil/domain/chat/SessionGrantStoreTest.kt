package com.orgutil.domain.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins FM-S1 (授权缓存串会话): session grants must be scoped by session
 * id. A grant issued in one session must NOT auto-approve the same
 * tool+target in another session, and clearing one session's grants must
 * not clear another's.
 */
class SessionGrantStoreTest {

    @Test
    fun `grant in one session is invisible in another session`() {
        val store = SessionGrantStore()
        val key = store.key("org_write_file", "notes/gtd.org")

        store.grant("session-a", key)

        assertTrue(store.has("session-a", key))
        assertFalse("grant must not leak across sessions", store.has("session-b", key))
    }

    @Test
    fun `clearing one session leaves other sessions' grants intact`() {
        val store = SessionGrantStore()
        val key = store.key("org_write_file", "notes/gtd.org")

        store.grant("session-a", key)
        store.grant("session-b", key)
        store.clear("session-a")

        assertFalse(store.has("session-a", key))
        assertTrue("session-b grant must survive clearing session-a", store.has("session-b", key))
    }

    @Test
    fun `clearAll empties every session`() {
        val store = SessionGrantStore()
        val key = store.key("org_create_file", "notes/new.org")
        store.grant("s1", key)
        store.grant("s2", key)

        store.clearAll()

        assertFalse(store.has("s1", key))
        assertFalse(store.has("s2", key))
    }

    @Test
    fun `same tool different target stays separate grant`() {
        val store = SessionGrantStore()
        store.grant("s1", store.key("org_write_file", "a.org"))

        assertFalse(store.has("s1", store.key("org_write_file", "b.org")))
    }
}
