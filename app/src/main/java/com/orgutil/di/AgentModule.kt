package com.orgutil.di

import com.orgutil.data.agent.ProviderRegistry
import com.orgutil.data.agent.ResilientLlmClient
import com.orgutil.data.agent.ResolvedProvider
import com.orgutil.data.agent.SessionProviderPin
import com.orgutil.data.agent.tools.AgentToolRegistry
import com.orgutil.data.datasource.ChatSettingsStore
import com.orgutil.data.repository.ChatRepository
import com.orgutil.data.repository.CompactionConfig
import com.orgutil.data.repository.ContextCompactor
import com.orgutil.data.repository.LlmTranscriptSummarizer
import com.orgutil.domain.chat.AgentToolCatalog
import com.orgutil.domain.chat.ApprovalGate
import com.orgutil.domain.chat.DefaultApprovalGate
import com.orgutil.domain.chat.LlmClient
import com.orgutil.domain.chat.RunStateTracker
import com.orgutil.domain.chat.SessionGrantStore
import com.orgutil.domain.chat.TranscriptStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentProvidingModule {

    @Provides
    @Singleton
    fun provideSessionGrantStore(): SessionGrantStore = SessionGrantStore()

    /**
     * The app-wide LlmClient is the retry/fallback router: primary = the
     * session's profile (or the default), fallback = the user-configured
     * fallback profile only. Both resolve lazily per request so profile
     * edits and session switches apply on the next call.
     */
    @Provides
    @Singleton
    fun provideLlmClient(
        registry: ProviderRegistry,
        settings: ChatSettingsStore,
        pin: SessionProviderPin
    ): LlmClient = ResilientLlmClient(
        primaryProvider = {
            registry.effectiveProfile(pin.sessionId)?.let { profile ->
                registry.clientFor(profile)?.let { ResolvedProvider(profile.name, it) }
            }
        },
        fallbackProvider = {
            settings.fallbackProfileId()?.let { id ->
                registry.profileById(id)?.let { profile ->
                    registry.clientFor(profile)?.let { ResolvedProvider(profile.name, it) }
                }
            }
        }
    )

    @Provides
    @Singleton
    fun provideContextCompactor(
        chatRepository: ChatRepository,
        settings: ChatSettingsStore,
        llmClient: LlmClient,
        summarizer: LlmTranscriptSummarizer
    ): ContextCompactor = ContextCompactor(
        summaryStore = chatRepository,
        configProvider = {
            CompactionConfig(budgetTokens = settings.budgetTokens(), keepExchanges = settings.keepExchanges())
        },
        summarizer = { previous, excerpts ->
            summarizer.summarize(llmClient, CompactionConfig(budgetTokens = settings.budgetTokens()), previous, excerpts)
        }
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {

    @Binds
    abstract fun bindApprovalGate(impl: DefaultApprovalGate): ApprovalGate

    @Binds
    abstract fun bindAgentToolCatalog(impl: AgentToolRegistry): AgentToolCatalog

    @Binds
    abstract fun bindTranscriptStore(impl: ChatRepository): TranscriptStore

    @Binds
    abstract fun bindRunStateTracker(impl: ChatRepository): RunStateTracker
}
