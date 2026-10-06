package com.orgutil.data.repository

import com.orgutil.domain.chat.AgendaContextReference
import com.orgutil.domain.chat.skills.SkillSelection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Stores per-message Agenda context (and, since V2, the resolved skill
 * invocation) in the existing transcript content column.
 *
 * Backward compatibility: the prefix stays the historical one and the new
 * [Envelope.skill] field has a default, so pre-skill payloads decode exactly
 * as before (skill = null), and future readers tolerate old rows.
 */
internal object ChatMessageContextCodec {
    private const val PREFIX = "ORGUTIL_CHAT_CONTEXT_V1\n"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Envelope(
        val prompt: String,
        val references: List<AgendaContextReference>,
        val skill: SkillSelection? = null
    )

    data class Decoded(
        val prompt: String,
        val references: List<AgendaContextReference>,
        val skill: SkillSelection? = null
    )

    fun encode(prompt: String, references: List<AgendaContextReference>, skill: SkillSelection? = null): String {
        if (references.isEmpty() && skill == null) return prompt
        return PREFIX + json.encodeToString(Envelope.serializer(), Envelope(prompt, references, skill))
    }

    fun decode(stored: String): Decoded {
        if (!stored.startsWith(PREFIX)) return Decoded(stored, emptyList(), null)
        return runCatching {
            json.decodeFromString(Envelope.serializer(), stored.removePrefix(PREFIX)).let {
                Decoded(it.prompt, it.references, it.skill)
            }
        }.getOrDefault(Decoded(stored, emptyList(), null))
    }

    /**
     * Expands the saved references and skill contract only when rebuilding
     * the LLM conversation. The skill block re-renders from the persisted
     * content on EVERY rebuild, so resume and request trimming cannot lose
     * the run's skill context.
     */
    fun forModel(stored: String): String {
        val decoded = decode(stored)
        if (decoded.references.isEmpty() && decoded.skill == null) return decoded.prompt
        return buildString {
            append(decoded.prompt)
            decoded.skill?.let { skill ->
                append("\n\n[Skill /org activated - follow these instructions]\n")
                append(skill.instructions.trimEnd())
                if (skill.arguments.isNotBlank()) {
                    append("\n\nUser's additional instructions for /org: ")
                    append(skill.arguments)
                }
                if (skill.sources.isNotEmpty()) {
                    append("\n\nTrusted source items selected for this /org run (reference_id is the id to pass to org_integrate):\n")
                    skill.sources.forEachIndexed { index, source ->
                        append("\n[Source ").append(index + 1).append("] ")
                        append("reference_id=").append(source.referenceId).append('\n')
                        append("File: ").append(source.relativePath).append('\n')
                        append("Heading path: ").append(source.hierarchy).append('\n')
                        append("Raw subtree captured at send time:\n")
                        append(source.subtreeText.trimEnd()).append('\n')
                    }
                }
            }
            // When a skill carries raw source snapshots, those are the
            // authoritative copies (byte-exact, used for verified cleanup);
            // the rendered Agenda-reference block would only duplicate them.
            if (decoded.references.isNotEmpty() && decoded.skill?.sources.isNullOrEmpty()) {
                append("\nContext from the Agenda items selected for this message:\n")
                decoded.references.forEachIndexed { index, reference ->
                    append("\n[Agenda reference ").append(index + 1).append("] ")
                    reference.todo?.takeIf(String::isNotBlank)?.let { append(it).append(' ') }
                    append(reference.title).append('\n')
                    append("File: ").append(reference.fileName).append('\n')
                    append("Heading path: ").append(reference.hierarchy).append('\n')
                    reference.scheduled?.let { append("Scheduled: ").append(it).append('\n') }
                    reference.deadline?.let { append("Deadline: ").append(it).append('\n') }
                    if (reference.tags.isNotEmpty()) append("Tags: ").append(reference.tags.joinToString(", ")).append('\n')
                    if (reference.content.isNotBlank()) {
                        append("Content:\n").append(reference.content.trimEnd()).append('\n')
                    }
                }
            }
        }
    }

    /** Skill id persisted with this message, if any (for UI badges). */
    fun skillIdOf(stored: String): String? = decode(stored).skill?.skillId
}
