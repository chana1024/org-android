package com.orgutil.data.repository

import com.orgutil.data.database.entity.ChatMessageEntity
import com.orgutil.domain.chat.ToolArgumentException
import com.orgutil.domain.chat.skills.OrgIntegrateSkill
import com.orgutil.domain.chat.skills.SkillSelection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the skill invocation that governs the CURRENT run of a session,
 * straight from the persisted transcript - never from mutable in-memory
 * state, so nothing can leak across sessions or runs and resume keeps its
 * authority.
 *
 * Authority rule: the LAST user message carrying a skill selection is the
 * current run. If any later user message exists, that skill run was
 * superseded and its trusted references are void - old references from
 * earlier, unrelated messages are never authorized again.
 */
@Singleton
class SkillRunStore internal constructor(
    private val loadMessages: suspend (sessionId: String) -> List<ChatMessageEntity>
) {
    @Inject
    constructor(chatRepository: ChatRepository) : this({ sessionId -> chatRepository.getMessagesRaw(sessionId) })

    /**
     * The /org selection governing the current run, or null when this
     * session has no live /org run (no invocation yet, or it was superseded
     * by a later plain user message, or it is not the org-integrate skill).
     */
    suspend fun activeOrgIntegration(sessionId: String): SkillSelection? {
        val messages = loadMessages(sessionId)
        val lastSkill = messages.lastOrNull { it.role == "user" && skillOf(it) != null } ?: return null
        val superseded = messages.any { it.role == "user" && it.createdAt > lastSkill.createdAt }
        if (superseded) return null
        val selection = skillOf(lastSkill) ?: return null
        return selection.takeIf { it.skillId == OrgIntegrateSkill.ID && it.sources.isNotEmpty() }
    }

    private fun skillOf(message: ChatMessageEntity): SkillSelection? =
        ChatMessageContextCodec.decode(message.content).skill

    /**
     * Native per-run mutation restriction for /org: while an org-integrate
     * run governs the session, the general note-editing tools must refuse -
     * instruction text alone is not a safety boundary. org_integrate itself
     * is the only sanctioned mutation path for such a run.
     */
    suspend fun assertOrgRunAllowsDirectEdits(sessionId: String) {
        val active = activeOrgIntegration(sessionId)
        if (active != null) {
            throw ToolArgumentException(
                "This session is in an active /org run: note edits are coordinated by org_integrate only. " +
                    "Build the final destination contents and call org_integrate with them; direct " +
                    "file edits would bypass verified source cleanup."
            )
        }
    }
}
