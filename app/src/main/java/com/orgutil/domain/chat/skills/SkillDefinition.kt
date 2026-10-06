package com.orgutil.domain.chat.skills

/**
 * A reusable chat skill: a typed, built-in capability invoked by a slash
 * command at the start of a user message. Skills are data (id, command,
 * instruction text), never downloaded code - the only executable surface a
 * skill can rely on is the existing agent tool registry.
 */
data class SkillDefinition(
    /** Stable identifier persisted in the transcript (e.g. "org-integrate"). */
    val id: String,
    /** Canonical slash command, lowercase, starts with '/' and contains no whitespace. */
    val command: String,
    val name: String,
    /** One-line summary for the skill picker. */
    val description: String,
    /** Instruction text version; persisted alongside each invocation. */
    val version: Int,
    /** Full instruction resource handed to the model for this skill's runs. */
    val instructions: String,
    /** The command refuses to run without at least one attached Agenda reference. */
    val requiresAgendaReferences: Boolean = false
)

/** Result of recognizing a leading slash command in a user draft. */
sealed class SlashCommand {
    /** No leading slash: an ordinary message. */
    data object None : SlashCommand()

    /** A known skill command; [arguments] is the remaining prose (may be blank). */
    data class Skill(
        val definition: SkillDefinition,
        val arguments: String,
        val rawText: String
    ) : SlashCommand()

    /**
     * A local session command executed by the app itself, before anything is
     * sent to a model. Never enters the transcript and never reaches the
     * agent loop.
     */
    data class Session(val definition: SessionCommandDefinition, val rawText: String) : SlashCommand()

    /** A leading slash token that matches no known command. */
    data class Unknown(val token: String, val rawText: String) : SlashCommand()
}

/**
 * A built-in local session command (e.g. "/fork"). Unlike a
 * [SkillDefinition] it carries no model instructions: it is a pure client
 * action on the chat session list, resolved and executed by the ViewModel.
 */
data class SessionCommandDefinition(
    /** Canonical slash command, lowercase, starts with '/' and contains no whitespace. */
    val command: String,
    val name: String,
    /** One-line summary for the slash menu. */
    val description: String
)

/**
 * The built-in local session command catalog. Recognized at the same
 * position as skills (start of message, exact token boundary) and shown in
 * the same slash menu, but executed locally. Intentionally minimal: only
 * commands that add something the UI cannot already do — starting a fresh
 * chat is the toolbar's 新会话 action, so there is deliberately no /clear.
 */
object SessionCommands {

    /** Forks the current chat into an independent new session. */
    val FORK = SessionCommandDefinition(
        command = "/fork",
        name = "复制会话",
        description = "把当前会话（含上下文、引用与模型选择）复制为一个独立新会话，原会话保留"
    )

    val ALL = listOf(FORK)

    private val byCommand = ALL.associateBy { it.command }

    fun byCommand(token: String): SessionCommandDefinition? = byCommand[token]
}

/**
 * The built-in skill catalog. Slash commands are recognized ONLY at the
 * beginning of a message and only on exact token boundaries: "/org" and
 * "/org extra text" match, "/organization" does not.
 */
object ChatSkillRegistry {

    val skills: List<SkillDefinition> = listOf(OrgIntegrateSkill.DEFINITION)

    private val byCommand = skills.associateBy { it.command }

    fun byId(id: String): SkillDefinition? = skills.find { it.id == id }

    fun recognize(message: String): SlashCommand {
        val trimmed = message.trim()
        if (!trimmed.startsWith("/")) return SlashCommand.None
        val token = trimmed.takeWhile { !it.isWhitespace() }
        val definition = byCommand[token]
        if (definition != null) {
            return SlashCommand.Skill(
                definition = definition,
                arguments = trimmed.removePrefix(token).trim(),
                rawText = trimmed
            )
        }
        val sessionCommand = SessionCommands.byCommand(token)
        if (sessionCommand != null) {
            return SlashCommand.Session(definition = sessionCommand, rawText = trimmed)
        }
        return SlashCommand.Unknown(token = token, rawText = trimmed)
    }
}
