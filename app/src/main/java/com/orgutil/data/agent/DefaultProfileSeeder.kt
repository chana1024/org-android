package com.orgutil.data.agent

import com.orgutil.data.database.entity.LlmProviderProfileEntity
import java.util.UUID

/**
 * FM-P9: the pre-provider global Anthropic credentials (encrypted prefs)
 * lazily seed ONE default profile on first use after the upgrade, so an
 * existing setup keeps working without re-entering the key. Explicit
 * profiles always win - seeding never runs once a profile exists.
 */
object DefaultProfileSeeder {

    fun seed(legacyKey: String?, legacyModel: String?, now: Long = System.currentTimeMillis()): LlmProviderProfileEntity? {
        val key = legacyKey?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        return LlmProviderProfileEntity(
            id = UUID.randomUUID().toString(),
            name = "Anthropic（默认）",
            type = ProviderType.ANTHROPIC.name,
            baseUrl = AnthropicLlmClient.DEFAULT_BASE_URL,
            model = legacyModel?.trim().takeIf { !it.isNullOrBlank() } ?: AnthropicLlmClient.DEFAULT_MODEL,
            isDefault = true,
            createdAt = now,
            updatedAt = now
        )
    }
}

/** Wire protocol selector for provider profiles. */
enum class ProviderType(val label: String) {
    ANTHROPIC("Anthropic Messages SSE"),
    OPENAI_COMPATIBLE("OpenAI 兼容 Chat Completions SSE"),
    RESPONSES("OpenAI Responses 协议（OpenAI / GLM / DeepSeek）")
}
