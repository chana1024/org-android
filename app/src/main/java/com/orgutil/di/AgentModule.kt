package com.orgutil.di

import com.orgutil.data.agent.AnthropicLlmClient
import com.orgutil.data.agent.tools.AgentToolRegistry
import com.orgutil.data.repository.ChatRepository
import com.orgutil.domain.chat.AgentToolCatalog
import com.orgutil.domain.chat.ApprovalGate
import com.orgutil.domain.chat.DefaultApprovalGate
import com.orgutil.domain.chat.LlmClient
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
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {

    @Binds
    abstract fun bindLlmClient(impl: AnthropicLlmClient): LlmClient

    @Binds
    abstract fun bindApprovalGate(impl: DefaultApprovalGate): ApprovalGate

    @Binds
    abstract fun bindAgentToolCatalog(impl: AgentToolRegistry): AgentToolCatalog

    @Binds
    abstract fun bindTranscriptStore(impl: ChatRepository): TranscriptStore
}
