package com.orgutil.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orgutil.data.agent.AnthropicLlmClient
import com.orgutil.data.datasource.ChatCredentialStore
import com.orgutil.data.repository.ChatRepository
import com.orgutil.domain.chat.AgentLoop
import com.orgutil.domain.chat.AgentMode
import com.orgutil.domain.chat.ApprovalDecision
import com.orgutil.domain.chat.ApprovalState
import com.orgutil.domain.chat.ChatStreamEvent
import com.orgutil.domain.chat.ChatMessageView
import com.orgutil.domain.chat.DecisionSource
import com.orgutil.domain.chat.DefaultApprovalGate
import com.orgutil.domain.chat.RiskLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

data class ChatUiState(
    val sessionId: String? = null,
    val mode: AgentMode = AgentMode.APPROVAL,
    val messages: List<ChatMessageView> = emptyList(),
    val pendingApproval: PendingApprovalUi? = null,
    val isRunning: Boolean = false,
    val liveAssistantText: String = "",
    val error: String? = null,
    val apiKeyConfigured: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val agentLoop: AgentLoop,
    private val approvalGate: DefaultApprovalGate,
    private val credentialStore: ChatCredentialStore,
    private val llmClient: AnthropicLlmClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState(apiKeyConfigured = credentialStore.isConfigured()))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null

    init {
        viewModelScope.launch {
            val session = chatRepository.ensureSession(AgentMode.APPROVAL, autoArmed = false)
            chatRepository.clearStreamingFlags(session.id)
            llmClient.apiKey = credentialStore.getApiKey() ?: ""
            llmClient.modelName = credentialStore.getModel()
            _uiState.update {
                it.copy(
                    sessionId = session.id,
                    mode = runCatching { AgentMode.valueOf(session.mode) }.getOrDefault(AgentMode.APPROVAL),
                    apiKeyConfigured = credentialStore.isConfigured()
                )
            }
            chatRepository.observeMessages(session.id).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
        // Mode is read live on every policy decision, so a mid-run switch
        // takes effect at the next tool call boundary.
        agentLoop.modeProvider = { _uiState.value.mode }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.isRunning) return
        val sessionId = _uiState.value.sessionId ?: return
        if (!credentialStore.isConfigured()) {
            _uiState.update { it.copy(error = "Set an LLM API key first (menu above).") }
            return
        }
        _uiState.update { it.copy(isRunning = true, error = null, liveAssistantText = "") }
        runJob = viewModelScope.launch {
            try {
                agentLoop.run(sessionId, trimmed).collect { event ->
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
                        else -> Unit
                    }
                }
            } finally {
                _uiState.update { it.copy(isRunning = false, pendingApproval = null) }
            }
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

    fun saveApiKey(key: String) {
        credentialStore.storeApiKey(key)
        llmClient.apiKey = key.trim()
        _uiState.update { it.copy(apiKeyConfigured = credentialStore.isConfigured(), error = null) }
    }
}
