package com.orgutil.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A named LLM provider configuration. [type] selects the wire protocol:
 * "anthropic" (Messages SSE), "openai_compatible" (Chat Completions SSE) or
 * "responses" (OpenAI Responses protocol - OpenAI / GLM native / DeepSeek).
 *
 * Hosted web search is a per-profile switch:
 * - [providerKind]: which provider this points at (AUTO = detect from the
 *   base URL). Search capability honestly differs per provider x protocol.
 * - [webSearchEnabled]: send the provider's own hosted search tool.
 * - [searchSupportOverride]: explicit user assertion ("supported") that an
 *   UNDOCUMENTED custom endpoint accepts the search tool. It can never
 *   override a documented "ignored" route (e.g. DeepSeek Responses).
 *
 * NO secrets here by design: the API key lives in the encrypted
 * ChatCredentialStore under key "chat_profile_key_<id>". Legacy global
 * credentials lazily seed the default profile (see ProviderConfigSource).
 */
@Entity(tableName = "llm_provider_profile")
data class LlmProviderProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String, // ProviderType.name
    val baseUrl: String,
    val model: String,
    val isDefault: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val providerKind: String? = null, // ProviderKind.name; null = AUTO
    val webSearchEnabled: Boolean = false,
    val searchSupportOverride: String? = null // WebSearchSupport.OVERRIDE_SUPPORTED
)
