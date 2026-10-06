package com.orgutil.domain.chat

/**
 * Pure rule for which session is active after (re)start. [orderedIds] is
 * the session list ordered by updatedAt DESC - the same order the DAO
 * returns. Returns the id to activate, or null when a new session must be
 * created. See FM-S2: the persisted active id wins whenever it still
 * exists, so merely touching an old session's transcript (which bumps its
 * updatedAt) can never hijack the active session.
 */
object ActiveSessionResolver {
    fun resolve(savedId: String?, orderedIds: List<String>): String? {
        if (orderedIds.isEmpty()) return null
        if (savedId != null && orderedIds.contains(savedId)) return savedId
        return orderedIds.first()
    }
}
