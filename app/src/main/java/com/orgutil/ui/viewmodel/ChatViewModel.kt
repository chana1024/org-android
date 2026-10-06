package com.orgutil.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orgutil.data.agent.ProviderRegistry
import com.orgutil.data.agent.ProviderType
import com.orgutil.data.agent.ProviderKind
import com.orgutil.data.agent.SessionProviderPin
import com.orgutil.data.agent.WebSearchSupport
import com.orgutil.data.database.entity.LlmProviderProfileEntity
import com.orgutil.data.datasource.ChatCredentialStore
import com.orgutil.data.datasource.ChatSettingsStore
import com.orgutil.data.repository.ChatRepository
import com.orgutil.data.repository.ChatMessageContextCodec
import com.orgutil.data.repository.CompactionResult
import com.orgutil.data.repository.ContextCompactor
import com.orgutil.data.repository.OrgIntegrationService
import com.orgutil.data.repository.ResumeAnalyzer
import com.orgutil.domain.agenda.OrgAgendaEntry
import com.orgutil.domain.chat.AgendaContextReference
import com.orgutil.domain.chat.AgentLoop
import com.orgutil.domain.chat.AgentMode
import com.orgutil.domain.chat.ApprovalDecision
import com.orgutil.domain.chat.ChatStreamEvent
import com.orgutil.domain.chat.ChatMessageView
import com.orgutil.domain.chat.DefaultApprovalGate
import com.orgutil.domain.chat.RiskLevel
import com.orgutil.domain.chat.SessionGrantStore
import com.orgutil.domain.chat.skills.SessionCommandDefinition
import com.orgutil.domain.chat.skills.SessionCommands
import com.orgutil.domain.chat.skills.SkillSelection
import com.orgutil.domain.chat.skills.SlashCommand
import com.orgutil.domain.chat.skills.ChatSkillRegistry
import com.orgutil.domain.model.OrgNode
import com.orgutil.domain.repository.OrgFileRepository
import com.orgutil.domain.usecase.GetOrgAgendaUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The tool call currently waiting on an approval card. */
data class PendingApprovalUi(
    val messageId: String,
    val requestId: String,
    val toolName: String,
    val argsDigest: String,
    val riskLevel: RiskLevel,
    val sessionGrantAllowed: Boolean
)

/** One row of the session switcher list. */
data class SessionListItem(
    val id: String,
    val title: String,
    /**
     * Last real activity time from session metadata; null when the row has
     * no trustworthy timestamp (legacy 0) — rendered as 时间未知, never as a
     * fabricated 1970 date.
     */
    val updatedAt: Long?,
    val messageCount: Int = 0,
    /** Latest persisted user/assistant text, single line, already trimmed. */
    val preview: String? = null,
    val isActive: Boolean,
    val runStatus: String
)

/** One row of the provider profile list. */
data class ProviderProfileUi(
    val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val model: String,
    val isDefault: Boolean,
    val hasKey: Boolean,
    val isSessionProfile: Boolean,
    val isFallback: Boolean,
    /** 联网搜索 switch state + honest capability label for this route. */
    val webSearchEnabled: Boolean = false,
    val webSearchLabel: String? = null,
    val webSearchReason: String? = null,
    val webSearchOverride: Boolean = false
)

/** Interrupted-run notice shown until the user resumes or discards it. */
data class ResumeNoticeUi(
    val sessionId: String,
    val prompt: String?,
    val pendingApprovals: Int,
    val unknownEffectTools: List<String>
)

/** Draft used by the profile editor dialog. */
data class ProviderProfileDraft(
    val id: String? = null,
    val name: String = "",
    val type: String = ProviderType.ANTHROPIC.name,
    val baseUrl: String = "https://api.anthropic.com",
    val model: String = "claude-sonnet-5",
    val apiKey: String = "", // blank = keep existing key when editing
    val providerKind: String = ProviderKind.AUTO.name,
    val webSearchEnabled: Boolean = false,
    val searchSupportOverride: Boolean = false
)

/**
 * Result of a profile save attempt. [id] is set once the profile row is
 * durable (even on later-step failure) so the editor retries as an UPDATE
 * against that exact row — never a duplicate insert under a new UUID.
 */
data class ProfileSaveOutcome(
    val success: Boolean,
    val id: String?
)

data class ChatUiState(
    val sessionId: String? = null,
    val sessionTitle: String = "Chat",
    val sessions: List<SessionListItem> = emptyList(),
    val mode: AgentMode = AgentMode.APPROVAL,
    val messages: List<ChatMessageView> = emptyList(),
    val pendingApproval: PendingApprovalUi? = null,
    val isRunning: Boolean = false,
    val liveAssistantText: String = "",
    val error: String? = null,
    val providerReady: Boolean = false,
    val activeProfileName: String? = null,
    val fallbackProfileName: String? = null,
    val profiles: List<ProviderProfileUi> = emptyList(),
    val budgetTokens: Long = 0,
    val resumeNotice: ResumeNoticeUi? = null,
    val compactionStatus: String? = null,
    val providerNotice: String? = null,
    /** True when providerNotice reports a failure (error color in the dialog). */
    val providerNoticeIsError: Boolean = false,
    val agendaContextOptions: List<AgendaContextOption> = emptyList(),
    val loadingAgendaContextOptions: Boolean = false,
    val selectedAgendaReferences: List<AgendaContextReference> = emptyList(),
    val loadingAgendaReferenceIds: Set<String> = emptySet(),
    /**
     * One-shot draft restore: a skill run whose send-time capture failed
     * hands the composer its text back instead of silently eating it.
     */
    val pendingDraft: String? = null
)

data class AgendaContextOption(
    val id: String,
    val fileName: String,
    val hierarchy: String,
    val entry: OrgAgendaEntry
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val agentLoop: AgentLoop,
    private val approvalGate: DefaultApprovalGate,
    private val credentialStore: ChatCredentialStore,
    private val sessionGrants: SessionGrantStore,
    private val contextCompactor: ContextCompactor,
    private val chatSettings: ChatSettingsStore,
    private val providerRegistry: ProviderRegistry,
    private val providerPin: SessionProviderPin,
    private val getOrgAgendaUseCase: GetOrgAgendaUseCase,
    private val orgFileRepository: OrgFileRepository,
    private val orgIntegrationService: OrgIntegrationService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState(budgetTokens = chatSettings.budgetTokens()))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null
    private var messagesJob: Job? = null

    init {
        viewModelScope.launch {
            // Runs left mid-flight by a dead process become INTERRUPTED and
            // their suspended approvals are voided (startup recovery pass).
            val interruptedIds = chatRepository.markInterruptedRuns().map { it.id }.toSet()
            val session = chatRepository.restoreActiveSession(AgentMode.APPROVAL, autoArmed = false)
            chatRepository.clearStreamingFlags(session.id)
            providerPin.sessionId = session.id
            _uiState.update {
                it.copy(
                    sessionId = session.id,
                    sessionTitle = session.title,
                    mode = runCatching { AgentMode.valueOf(session.mode) }.getOrDefault(AgentMode.APPROVAL)
                )
            }
            if (session.id in interruptedIds) {
                val report = ResumeAnalyzer.analyze(chatRepository.getMessagesRaw(session.id))
                if (report.hasAnyUserMessage) {
                    _uiState.update {
                        it.copy(
                            resumeNotice = ResumeNoticeUi(
                                sessionId = session.id,
                                prompt = report.lastUserPrompt,
                                pendingApprovals = report.pendingApprovals,
                                unknownEffectTools = report.unknownEffectTools.map { issue ->
                                    "${issue.toolName} ${issue.argsDigest}"
                                }
                            )
                        )
                    }
                }
            }
            wireRequestBuilder()
            observeMessagesFor(session.id)
            launch { refreshProviderState() }
            chatRepository.observeSessions().collect { rows ->
                val activeId = _uiState.value.sessionId
                _uiState.update { state ->
                    state.copy(
                        // Order comes straight from the DAO's deterministic
                        // activity sort; only row->UI mapping happens here
                        // (no timestamps are assigned on read).
                        sessions = rows.map { row ->
                            SessionListItem(
                                id = row.session.id,
                                title = row.session.title,
                                updatedAt = row.session.updatedAt.takeIf { it > 0 },
                                messageCount = row.messageCount,
                                preview = row.preview?.trim()?.takeIf { it.isNotEmpty() },
                                isActive = row.session.id == activeId,
                                runStatus = row.session.runStatus
                            )
                        },
                        sessionTitle = rows.find { it.session.id == activeId }?.session?.title
                            ?: state.sessionTitle
                    )
                }
            }
        }
        viewModelScope.launch {
            chatRepository.observeProfiles().collect { refreshProviderState() }
        }
        // Mode is read live on every policy decision, so a mid-run switch
        // takes effect at the next tool call boundary.
        agentLoop.modeProvider = { _uiState.value.mode }
    }

    /** Recomputes profile list / effective + fallback provider readiness. */
    private suspend fun refreshProviderState() {
        val profiles = providerRegistry.profiles()
        val sessionId = _uiState.value.sessionId
        val effective = providerRegistry.effectiveProfile(sessionId)
        val effectiveClient = effective?.let { providerRegistry.clientFor(it) }
        val fallbackId = chatSettings.fallbackProfileId()
        _uiState.update { state ->
            state.copy(
                profiles = profiles.map { p ->
                    val kind = runCatching { ProviderKind.valueOf(p.providerKind ?: "") }
                        .getOrDefault(ProviderKind.AUTO)
                    val support = runCatching {
                        WebSearchSupport.resolve(
                            runCatching { ProviderType.valueOf(p.type) }.getOrDefault(ProviderType.ANTHROPIC),
                            p.baseUrl, kind, p.searchSupportOverride
                        )
                    }.getOrNull()
                    ProviderProfileUi(
                        id = p.id,
                        name = p.name,
                        type = p.type,
                        baseUrl = p.baseUrl,
                        model = p.model,
                        isDefault = p.isDefault,
                        hasKey = credentialStore.getProfileKey(p.id) != null ||
                            (p.isDefault && credentialStore.getApiKey() != null),
                        isSessionProfile = p.id == effective?.id && sessionId?.let { sid ->
                            chatRepository.profileIdOf(sid) == p.id
                        } == true,
                        isFallback = p.id == fallbackId,
                        webSearchEnabled = p.webSearchEnabled,
                        webSearchLabel = support?.let { "${it.label}${if (p.webSearchEnabled) " · 已开启" else ""}" },
                        webSearchReason = support?.reason,
                        webSearchOverride = p.searchSupportOverride != null
                    )
                },
                providerReady = effective != null && effectiveClient != null,
                activeProfileName = effective?.name,
                fallbackProfileName = fallbackId?.let { id -> profiles.find { it.id == id }?.name },
                budgetTokens = chatSettings.budgetTokens()
            )
        }
    }

    /**
     * All model requests go through the compactor: under budget = full
     * transcript; over budget = persisted summary + pairing-safe window;
     * summarizer failure = bounded window with a visible status (never a
     * silent drop).
     */
    private fun wireRequestBuilder() {
        agentLoop.conversationBudgetTokens = chatSettings.budgetTokens()
        agentLoop.requestBuilder = { sessionId ->
            val result = contextCompactor.buildRequest(sessionId, chatRepository.getMessagesRaw(sessionId))
            val status = when (result) {
                is CompactionResult.Applied ->
                    "已压缩早期对话为摘要（摘要 ${result.summarizedCount} 条 · 近期窗口 ${result.keptCount} 条）"
                is CompactionResult.FallbackWindow -> result.reason
                is CompactionResult.Full -> null
            }
            if (_uiState.value.sessionId == sessionId) {
                _uiState.update { it.copy(compactionStatus = status) }
            }
            result.messages
        }
    }

    private fun observeMessagesFor(sessionId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            chatRepository.observeMessages(sessionId).collect { messages ->
                // Guard against a stale collector writing the previous
                // session's transcript into the new session's UI (FM-S4).
                if (_uiState.value.sessionId == sessionId) {
                    _uiState.update { it.copy(messages = messages) }
                }
            }
        }
    }

    // ---- session lifecycle ----

    fun newSession() {
        stop()
        viewModelScope.launch {
            val session = chatRepository.createSession(_uiState.value.mode, autoArmed = false)
            activateSession(session.id)
        }
    }

    fun switchSession(sessionId: String) {
        if (sessionId == _uiState.value.sessionId) return
        // FM-S4: a run may never keep writing into a session the user left.
        stop()
        viewModelScope.launch { chatRepository.setActiveSession(sessionId)?.let { activateSession(it.id) } }
    }

    private suspend fun activateSession(sessionId: String) {
        _uiState.value.sessionId?.let { sessionGrants.clear(it) }
        _uiState.update { state ->
            state.copy(
                sessionId = sessionId,
                messages = emptyList(),
                pendingApproval = null,
                error = null,
                liveAssistantText = "",
                resumeNotice = null,
                compactionStatus = null,
                selectedAgendaReferences = emptyList(),
                loadingAgendaReferenceIds = emptySet()
            )
        }
        providerPin.sessionId = sessionId
        chatRepository.clearStreamingFlags(sessionId)
        observeMessagesFor(sessionId)
        chatRepository.setActiveSession(sessionId)?.let { session ->
            val interruptedNotice = if (session.runStatus == "INTERRUPTED") {
                val report = ResumeAnalyzer.analyze(chatRepository.getMessagesRaw(session.id))
                if (report.hasAnyUserMessage) {
                    ResumeNoticeUi(session.id, report.lastUserPrompt, report.pendingApprovals, report.unknownEffectTools.map { "${it.toolName} ${it.argsDigest}" })
                } else null
            } else null
            _uiState.update {
                it.copy(
                    sessionTitle = session.title,
                    mode = runCatching { AgentMode.valueOf(session.mode) }.getOrDefault(AgentMode.APPROVAL),
                    resumeNotice = interruptedNotice
                )
            }
        }
        refreshProviderState()
    }

    fun renameSession(sessionId: String, title: String) {
        viewModelScope.launch { chatRepository.renameSession(sessionId, title) }
    }

    /** Deletes a session; the repository picks the next active one (or null → fresh). */
    fun deleteSession(sessionId: String) {
        stop()
        viewModelScope.launch {
            val isCurrent = sessionId == _uiState.value.sessionId
            sessionGrants.clear(sessionId)
            val next = chatRepository.deleteSession(sessionId)
            if (isCurrent) {
                val target = next ?: chatRepository.createSession(AgentMode.APPROVAL, autoArmed = false)
                activateSession(target.id)
            }
        }
    }

    // ---- provider profiles ----

    /**
     * Persists a profile and reports the outcome back to the editor dialog.
     * The dialog closes ONLY on success — on failure the typed draft (API
     * key included) stays on screen for a retry. [ProfileSaveOutcome.id] is
     * non-null as soon as the profile ROW is durable, even if a later step
     * (key write, default promotion) failed: a retry then updates that same
     * row instead of inserting a duplicate under a fresh UUID.
     */
    fun saveProfile(draft: ProviderProfileDraft, onResult: (ProfileSaveOutcome) -> Unit) {
        viewModelScope.launch {
            var persistedId: String? = null
            val success = try {
                // Lookup + construction live inside the failure boundary:
                // a DB error here must also keep the editor open.
                val now = System.currentTimeMillis()
                val existing = draft.id?.let { providerRegistry.profileById(it) }
                val profile = LlmProviderProfileEntity(
                    id = draft.id ?: UUID.randomUUID().toString(),
                    name = draft.name.trim().ifBlank { "未命名" },
                    type = draft.type,
                    baseUrl = draft.baseUrl.trim().ifBlank { "https://api.anthropic.com" },
                    model = draft.model.trim().ifBlank { "claude-sonnet-5" },
                    isDefault = existing?.isDefault ?: false,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    providerKind = draft.providerKind.takeIf { it.isNotBlank() },
                    webSearchEnabled = draft.webSearchEnabled,
                    searchSupportOverride = if (draft.searchSupportOverride) {
                        WebSearchSupport.OVERRIDE_SUPPORTED
                    } else null
                )
                if (existing == null) {
                    chatRepository.insertProfile(profile)
                } else {
                    chatRepository.updateProfile(profile)
                }
                // Row is durable: from this point every retry must target it.
                persistedId = profile.id
                if (draft.apiKey.isNotBlank()) {
                    credentialStore.storeProfileKey(profile.id, draft.apiKey)
                }
                if (existing == null && providerRegistry.profiles().size == 1) {
                    // First profile becomes the default so the app is usable.
                    chatRepository.setDefaultProfile(profile)
                }
                providerRegistry.invalidate(profile.id)
                _uiState.update {
                    it.copy(
                        providerNotice = "已保存供应商配置「${profile.name}」",
                        providerNoticeIsError = false
                    )
                }
                true
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Generic message only: exception text can echo the user's
                // own input (e.g. a constraint violation quoting a value).
                // The exception TYPE is safe and aids diagnosis.
                _uiState.update {
                    it.copy(
                        providerNotice = "保存供应商配置失败（${e.javaClass.simpleName}）。" +
                            "内容仍保留在编辑窗口，可直接重试。",
                        providerNoticeIsError = true
                    )
                }
                false
            }
            onResult(ProfileSaveOutcome(success, persistedId))
            // Refresh even after a failed save so the list always mirrors
            // what actually got persisted.
            try {
                refreshProviderState()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Refresh is best-effort; the outcome was already reported.
            }
        }
    }

    /** Clears the save/edit notice so stale feedback cannot linger. */
    fun clearProviderNotice() {
        _uiState.update { it.copy(providerNotice = null, providerNoticeIsError = false) }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            chatRepository.deleteProfileCascade(profileId)
            providerRegistry.invalidate(profileId)
            if (chatSettings.fallbackProfileId() == profileId) chatSettings.setFallbackProfileId(null)
            refreshProviderState()
        }
    }

    fun setDefaultProfile(profileId: String) {
        viewModelScope.launch {
            providerRegistry.profileById(profileId)?.let {
                chatRepository.setDefaultProfile(it)
                providerRegistry.invalidate(profileId)
                refreshProviderState()
            }
        }
    }

    /** Per-session provider override; null clears back to the default profile. */
    fun setSessionProfile(profileId: String?) {
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch {
            chatRepository.setSessionProfile(sessionId, profileId)
            refreshProviderState()
        }
    }

    fun setFallbackProfile(profileId: String?) {
        chatSettings.setFallbackProfileId(profileId)
        viewModelScope.launch { refreshProviderState() }
    }

    fun setBudgetTokens(value: Long) {
        chatSettings.setBudgetTokens(value)
        agentLoop.conversationBudgetTokens = chatSettings.budgetTokens()
        _uiState.update { it.copy(budgetTokens = chatSettings.budgetTokens()) }
    }

    // ---- run ----

    /**
     * Sends the draft. Slash commands are resolved HERE, before anything is
     * submitted: local session commands (/fork) are executed directly,
     * unknown commands and /org without usable references are rejected
     * locally without spending a model call, keeping the draft and the
     * attached reference chips intact.
     */
    fun send(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val sessionId = _uiState.value.sessionId ?: return false
        val recognition = ChatSkillRegistry.recognize(trimmed)
        // Local session commands never need a provider and never reach the
        // model or the transcript - they run before every other gate.
        if (recognition is SlashCommand.Session) {
            return runSessionCommand(recognition.definition, sessionId)
        }
        if (_uiState.value.isRunning) return false
        if (!_uiState.value.providerReady) {
            _uiState.update { it.copy(error = "请先配置模型供应商与密钥（右上角钥匙图标）。") }
            return false
        }
        val definition = when (recognition) {
            is SlashCommand.Unknown -> {
                val known = (
                    ChatSkillRegistry.skills.map { s -> "${s.command} ${s.name}" } +
                        SessionCommands.ALL.map { c -> "${c.command} ${c.name}" }
                    ).joinToString("；")
                _uiState.update {
                    it.copy(error = "未知命令 ${recognition.token}。可用命令：$known。命令需写在消息开头。")
                }
                return false
            }
            is SlashCommand.Skill -> recognition.definition
            is SlashCommand.Session -> return false // dispatched above; unreachable
            SlashCommand.None -> null
        }
        if (_uiState.value.loadingAgendaReferenceIds.isNotEmpty()) {
            _uiState.update { it.copy(error = "正在读取所选 Agenda 条目，请稍等片刻再发送。") }
            return false
        }
        if (definition?.requiresAgendaReferences == true && _uiState.value.selectedAgendaReferences.isEmpty()) {
            _uiState.update {
                it.copy(error = "「${definition.command}」需要至少一个附加的 Agenda 引用：先点「引用」选择条目，再发送。")
            }
            return false
        }

        val references = _uiState.value.selectedAgendaReferences
        if (definition == null) {
            val persistedText = ChatMessageContextCodec.encode(trimmed, references)
            clearDraftState()
            runJob = collectRun(agentLoop.run(sessionId, trimmed, persistedUserText = persistedText))
            return true
        }

        // Skill run: capture the trusted sources with a verified fresh read
        // BEFORE clearing anything, so a failed capture keeps the draft and
        // the attachments exactly as they were.
        val skillRecognition = recognition as SlashCommand.Skill
        _uiState.update { it.copy(isRunning = true, error = null, liveAssistantText = "", resumeNotice = null) }
        viewModelScope.launch {
            val snapshots = orgIntegrationService.captureSources(references)
            if (_uiState.value.sessionId != sessionId) return@launch
            snapshots.fold(
                onSuccess = { sources ->
                    if (!_uiState.value.isRunning) {
                        // The user pressed stop (or the run was voided) while
                        // the capture was in flight: do not start a run they
                        // cancelled - hand the draft back instead.
                        _uiState.update { it.copy(pendingDraft = trimmed) }
                        return@launch
                    }
                    val selection = SkillSelection(
                        skillId = definition.id,
                        skillVersion = definition.version,
                        instructions = definition.instructions,
                        arguments = skillRecognition.arguments,
                        sources = sources
                    )
                    val persistedText = ChatMessageContextCodec.encode(trimmed, references, selection)
                    clearDraftState()
                    runJob = collectRun(agentLoop.run(sessionId, trimmed, persistedUserText = persistedText))
                },
                onFailure = { error ->
                    // Draft and attachments preserved; only the transient
                    // running flag from the capture phase is rolled back.
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            pendingDraft = trimmed,
                            error = "${definition.command} 未发送：${error.localizedMessage ?: "读取引用失败"}"
                        )
                    }
                }
            )
        }
        return true
    }

    private fun clearDraftState() {
        _uiState.update {
            it.copy(
                isRunning = true,
                error = null,
                liveAssistantText = "",
                resumeNotice = null,
                selectedAgendaReferences = emptyList(),
                loadingAgendaReferenceIds = emptySet()
            )
        }
    }

    // ---- local session commands (/fork) ----

    /**
     * Executes a local session command. These are pure client actions — no
     * provider, no model call, no transcript row (failure checklist F11).
     * While a run is live it refuses deterministically with a clear
     * instruction instead of cancelling implicitly or cloning mid-flight
     * (F1/F2); starting a fresh empty chat remains the toolbar's 新会话
     * action, so no /clear exists here.
     */
    private fun runSessionCommand(command: SessionCommandDefinition, sessionId: String): Boolean {
        val state = _uiState.value
        when (command) {
            SessionCommands.FORK -> {
                if (state.isRunning || state.pendingApproval != null) {
                    _uiState.update {
                        it.copy(error = "「${command.command}」需要会话空闲时执行：请先按停止结束当前运行。")
                    }
                    return false
                }
                viewModelScope.launch {
                    val forked = runCatching { chatRepository.forkSession(sessionId) }
                    // The user switched away mid-copy: the fork exists in the
                    // session list, but the visible session must not be
                    // yanked from under them (F9).
                    if (forked.isSuccess && _uiState.value.sessionId != sessionId) return@launch
                    forked.fold(
                        onSuccess = { fork -> activateSession(fork.id) },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(error = error.localizedMessage ?: "复制会话失败，请稍后重试。")
                            }
                        }
                    )
                }
                return true
            }

            else -> {
                // Unreachable while ALL holds exactly FORK; keeps the
                // command surface honest if that ever changes.
                _uiState.update { it.copy(error = "未知会话命令 ${command.command}。") }
                return false
            }
        }
    }

    fun loadAgendaContextOptions() {
        if (_uiState.value.loadingAgendaContextOptions) return
        _uiState.update { it.copy(loadingAgendaContextOptions = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val agenda = getOrgAgendaUseCase().getOrThrow()
                val roots = listOf(
                    agenda.daily.today, agenda.daily.nextActions, agenda.daily.vibing,
                    agenda.daily.sandbagging, agenda.daily.waiting, agenda.daily.done,
                    agenda.daily.cancelledDropped, agenda.daily.inbox,
                    agenda.weekly.nextDays, agenda.weekly.stuckProjects, agenda.weekly.vibing,
                    agenda.weekly.sandbagging, agenda.weekly.waiting, agenda.weekly.hold,
                    agenda.weekly.maybe, agenda.weekly.inbox,
                    agenda.projectControl.projects, agenda.projectControl.stuckProjects,
                    agenda.areaControl.areas, agenda.areaControl.neglectedAreas
                ).flatten()
                roots.asSequence()
                    .flatMap { it.flattened() }
                    .filter { it.sourceOffset >= 0 }
                    .filter { entry ->
                        entry.todo?.uppercase()?.let { it !in CHAT_CONTEXT_EXCLUDED_TODO_KEYWORDS } ?: true
                    }
                    .distinctBy { "${it.uri}#${it.sourceOffset}" }
                    .map { entry ->
                        AgendaContextOption(
                            id = "${entry.uri}#${entry.sourceOffset}",
                            fileName = entry.fileName,
                            hierarchy = (entry.parentTitles + entry.title).joinToString(" › "),
                            entry = entry
                        )
                    }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.hierarchy })
                    .toList()
            }.onSuccess { options ->
                _uiState.update { it.copy(agendaContextOptions = options, loadingAgendaContextOptions = false) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        loadingAgendaContextOptions = false,
                        error = "加载 Agenda 项目失败：${error.localizedMessage ?: "未知错误"}"
                    )
                }
            }
        }
    }

    fun toggleAgendaContext(option: AgendaContextOption) {
        val state = _uiState.value
        if (state.selectedAgendaReferences.any { it.id == option.id }) {
            _uiState.update { it.copy(selectedAgendaReferences = it.selectedAgendaReferences.filterNot { ref -> ref.id == option.id }) }
            return
        }
        if (option.id in state.loadingAgendaReferenceIds) return
        val requestSessionId = state.sessionId ?: return
        _uiState.update { it.copy(loadingAgendaReferenceIds = it.loadingAgendaReferenceIds + option.id, error = null) }
        viewModelScope.launch {
            val reference = runCatching { readAgendaReference(option) }
            if (_uiState.value.sessionId != requestSessionId) return@launch
            _uiState.update { current ->
                val remainingLoads = current.loadingAgendaReferenceIds - option.id
                reference.fold(
                    onSuccess = { ref ->
                        current.copy(
                            selectedAgendaReferences = if (current.selectedAgendaReferences.any { it.id == ref.id }) {
                                current.selectedAgendaReferences
                            } else current.selectedAgendaReferences + ref,
                            loadingAgendaReferenceIds = remainingLoads
                        )
                    },
                    onFailure = { error ->
                        current.copy(
                            loadingAgendaReferenceIds = remainingLoads,
                            error = "无法引用「${option.entry.title}」：${error.localizedMessage ?: "读取失败"}"
                        )
                    }
                )
            }
        }
    }

    fun removeAgendaContext(id: String) {
        _uiState.update { it.copy(selectedAgendaReferences = it.selectedAgendaReferences.filterNot { ref -> ref.id == id }) }
    }

    /** Called by the composer after it consumed a [ChatUiState.pendingDraft]. */
    fun consumePendingDraft() {
        if (_uiState.value.pendingDraft != null) {
            _uiState.update { it.copy(pendingDraft = null) }
        }
    }

    private suspend fun readAgendaReference(option: AgendaContextOption): AgendaContextReference {
        val entry = option.entry
        val document = orgFileRepository.readOrgFile(Uri.parse(entry.uri.toString())).getOrThrow()
        val node = document.nodes.firstNotNullOfOrNull { it.findBySourceOffset(entry.sourceOffset) }
            ?: error("议程条目已变化，请重新打开引用列表")
        if (node.title != entry.title || node.todo != entry.todo) {
            error("议程条目已变化，请重新打开引用列表")
        }
        val entryByOffset = entry.flattened().associateBy { it.sourceOffset }
        return AgendaContextReference(
            id = option.id,
            fileName = entry.fileName,
            title = entry.title,
            todo = entry.todo,
            hierarchy = option.hierarchy,
            scheduled = entry.scheduled?.let { date -> "$date${entry.scheduledTime?.let { " $it" } ?: ""}" },
            deadline = entry.deadline?.let { date -> "$date${entry.deadlineTime?.let { " $it" } ?: ""}" },
            tags = entry.tags.sorted(),
            content = renderOrgContext(node, entryByOffset)
        )
    }

    private fun renderOrgContext(node: OrgNode, entriesByOffset: Map<Int, OrgAgendaEntry>): String = buildString {
        fun appendNode(current: OrgNode) {
            val entry = entriesByOffset[current.sourceOffset]
            append("*".repeat(current.level.coerceIn(1, 6))).append(' ')
            (entry?.todo ?: current.todo)?.takeIf(String::isNotBlank)?.let { append(it).append(' ') }
            append(current.title)
            entry?.priority?.let { append(" [#").append(it).append(']') }
            if (!entry?.tags.isNullOrEmpty()) append(" :").append(entry!!.tags.sorted().joinToString(":" )).append(':')
            appendLine()
            entry?.scheduled?.let { date -> append("SCHEDULED: <").append(date).append(entry.scheduledTime?.let { " $it" } ?: "").appendLine(">") }
            entry?.deadline?.let { date -> append("DEADLINE: <").append(date).append(entry.deadlineTime?.let { " $it" } ?: "").appendLine(">") }
            current.content.trim().takeIf(String::isNotBlank)?.let { appendLine(it) }
            current.children.forEach(::appendNode)
        }
        appendNode(node)
    }.trim()

    private fun OrgAgendaEntry.flattened(): Sequence<OrgAgendaEntry> = sequence {
        yield(this@flattened)
        children.forEach { child -> yieldAll(child.flattened()) }
    }

    private fun OrgNode.findBySourceOffset(offset: Int): OrgNode? {
        if (sourceOffset == offset) return this
        return children.firstNotNullOfOrNull { it.findBySourceOffset(offset) }
    }

    private companion object {
        // Chat context choices should be actionable or actual captured items,
        // not GTD containers, someday/maybe candidates, or closed entries.
        val CHAT_CONTEXT_EXCLUDED_TODO_KEYWORDS = setOf(
            "PROJ", "AREA", "MAYBE", "DONE", "CANCELLED", "DROPPED"
        )
    }

    /**
     * Continues an interrupted run: replays the persisted transcript (with
     * explicit interrupted / unknown-state tool results) and lets the model
     * continue. Never re-executes past tool calls by itself.
     */
    fun resumeRun() {
        if (_uiState.value.isRunning) return
        val notice = _uiState.value.resumeNotice ?: return
        if (!_uiState.value.providerReady) {
            _uiState.update { it.copy(error = "请先配置模型供应商与密钥（右上角钥匙图标）。") }
            return
        }
        _uiState.update { it.copy(isRunning = true, error = null, liveAssistantText = "", resumeNotice = null) }
        runJob = collectRun(agentLoop.resume(notice.sessionId))
    }

    /** User gives up on the interrupted run: settle it and drop the banner. */
    fun discardInterruptedRun() {
        val notice = _uiState.value.resumeNotice ?: return
        viewModelScope.launch {
            chatRepository.voidPendingApprovals(notice.sessionId)
            chatRepository.markRunIdle(notice.sessionId)
        }
        _uiState.update { it.copy(resumeNotice = null) }
    }

    private fun collectRun(flow: Flow<ChatStreamEvent>): Job = viewModelScope.launch {
        try {
            flow.collect { event ->
                when (event) {
                    is ChatStreamEvent.AssistantDelta -> _uiState.update { state ->
                        state.copy(liveAssistantText = state.liveAssistantText + event.delta)
                    }
                    is ChatStreamEvent.AssistantDone -> _uiState.update { state ->
                        state.copy(liveAssistantText = "")
                    }
                    is ChatStreamEvent.ToolCallPending -> _uiState.update { state ->
                        state.copy(
                            pendingApproval = PendingApprovalUi(
                                messageId = event.messageId,
                                requestId = event.requestId,
                                toolName = event.toolName,
                                argsDigest = event.argsDigest,
                                riskLevel = event.riskLevel,
                                sessionGrantAllowed = event.sessionGrantAllowed
                            )
                        )
                    }
                    is ChatStreamEvent.ToolCallResolved -> _uiState.update { state ->
                        state.copy(pendingApproval = null)
                    }
                    is ChatStreamEvent.RunFailed -> _uiState.update { state ->
                        state.copy(error = event.message, isRunning = false)
                    }
                    is ChatStreamEvent.RunFinished -> _uiState.update { state ->
                        state.copy(isRunning = false, liveAssistantText = "")
                    }
                    is ChatStreamEvent.ContextTrimmed -> _uiState.update { state ->
                        state.copy(
                            compactionStatus = "已达预算：本次请求丢弃最早 ${event.droppedExchanges} 组未压缩对话" +
                                "（完整历史仍保留在会话记录中）"
                        )
                    }
                    is ChatStreamEvent.ProviderNotice -> _uiState.update { state ->
                        state.copy(providerNotice = event.message)
                    }
                    is ChatStreamEvent.ToolCallFinished, is ChatStreamEvent.ToolCallRunning,
                    is ChatStreamEvent.UserMessageAdded -> Unit
                }
            }
        } finally {
            _uiState.update { it.copy(isRunning = false, pendingApproval = null) }
        }
    }

    fun approveOnce() = answer(ApprovalDecision.ApproveOnce)
    fun approveSession() = answer(ApprovalDecision.ApproveSession)
    fun deny() = answer(ApprovalDecision.Deny())

    private fun answer(decision: ApprovalDecision) {
        val pending = _uiState.value.pendingApproval ?: return
        approvalGate.answer(pending.requestId, decision)
    }

    fun stop() {
        runJob?.cancel()
        _uiState.value.sessionId?.let { sessionId ->
            viewModelScope.launch {
                chatRepository.voidPendingApprovals(sessionId)
                chatRepository.markRunIdle(sessionId)
            }
        }
        approvalGate.voidPending()
        _uiState.update { it.copy(isRunning = false, pendingApproval = null) }
    }

    /** Switches mode; AUTO requires the arming confirmation from the UI. */
    fun switchMode(mode: AgentMode, autoArmed: Boolean) {
        val sessionId = _uiState.value.sessionId ?: return
        _uiState.update { it.copy(mode = mode) }
        viewModelScope.launch {
            chatRepository.setMode(sessionId, mode, autoArmed)
        }
    }
}
