package com.orgutil.data.agent

import com.orgutil.data.database.dao.ChatDao
import com.orgutil.data.database.entity.LlmProviderProfileEntity
import com.orgutil.data.datasource.ChatCredentialStore
import com.orgutil.domain.chat.LlmClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves which provider serves a request and hands out ready clients.
 *
 * Resolution order: the session's own profileId (if that profile still
 * exists) -> the default profile -> the legacy global credentials (which
 * lazily seed a default profile once, see [DefaultProfileSeeder]) -> null.
 *
 * Clients are cached per profile revision (id + updatedAt + key identity),
 * so switching profiles or editing a key takes effect on the next request
 * while idle clients are reused. API keys never leave the encrypted store
 * except into the client's Authorization header.
 */
@Singleton
class ProviderRegistry @Inject constructor(
    private val chatDao: ChatDao,
    private val credentialStore: ChatCredentialStore
) {

    private val cache = ConcurrentHashMap<String, LlmClient>()
    private val cacheStamp = ConcurrentHashMap<String, String>()

    /** Seeds the default profile from legacy credentials exactly once. */
    suspend fun ensureDefaultSeeded() {
        if (chatDao.getProfiles().isNotEmpty()) return
        val seeded = DefaultProfileSeeder.seed(
            legacyKey = credentialStore.getApiKey(),
            legacyModel = credentialStore.getModel()
        ) ?: return
        chatDao.insertProfile(seeded)
    }

    suspend fun profiles(): List<LlmProviderProfileEntity> {
        ensureDefaultSeeded()
        return chatDao.getProfiles()
    }

    suspend fun profileById(id: String): LlmProviderProfileEntity? = chatDao.getProfile(id)

    suspend fun defaultProfile(): LlmProviderProfileEntity? {
        ensureDefaultSeeded()
        return chatDao.getDefaultProfile() ?: chatDao.getProfiles().firstOrNull()
    }

    /** Effective profile for a session: session override -> default. */
    suspend fun effectiveProfile(sessionId: String?): LlmProviderProfileEntity? {
        if (sessionId != null) {
            chatDao.getSession(sessionId)?.profileId?.let { chatDao.getProfile(it) }?.let { return it }
        }
        return defaultProfile()
    }

    /** Ready client for the profile; null when the profile has no stored key. */
    fun clientFor(profile: LlmProviderProfileEntity): LlmClient? {
        val profileType = runCatching { ProviderType.valueOf(profile.type) }.getOrNull()
        val profileKey = credentialStore.getProfileKey(profile.id)
        // The LEGACY GLOBAL KEY is an Anthropic credential: it may back the
        // default ANTHROPIC profile only, never an OpenAI-compatible one
        // (keys do not transfer across providers).
        val legacyKey = if (profile.isDefault && profileType == ProviderType.ANTHROPIC) {
            credentialStore.getApiKey()
        } else null
        val key = profileKey ?: legacyKey ?: return null
        // Hosted search only fires on routes the capability check allows;
        // Unsupported routes (e.g. DeepSeek Responses, which the provider
        // documents as ignoring web_search) never get a search adapter.
        val kind = runCatching { ProviderKind.valueOf(profile.providerKind ?: "") }.getOrDefault(ProviderKind.AUTO)
        val searchSupport = WebSearchSupport.resolve(
            profileType ?: ProviderType.ANTHROPIC, profile.baseUrl, kind, profile.searchSupportOverride
        )
        val searchActive = profile.webSearchEnabled && searchSupport !is WebSearchSupport.Unsupported
        // Key identity in the stamp is a hash prefix (never the key itself);
        // same-length key swaps still produce a different stamp. Search
        // toggles are part of the stamp so a flip rebuilds the client.
        val stamp = "${profile.id}|${profile.updatedAt}|${profile.model}|${profile.baseUrl}|" +
            "${profile.type}|${profile.providerKind}|$searchActive|${profile.searchSupportOverride}|" +
            keyIdentity(key)
        val cached = cache[profile.id]
        if (cached != null && cacheStamp[profile.id] == stamp) return cached
        // Full documented tool definition only for api.anthropic.com itself;
        // compatible layers get the minimal {type,name} form.
        val effectiveKind = kind.effective(profile.baseUrl)
        val client: LlmClient = when (profileType) {
            ProviderType.OPENAI_COMPATIBLE ->
                OpenAiCompatLlmClient(
                    baseUrl = profile.baseUrl, apiKey = key, modelName = profile.model,
                    webSearchEnabled = searchActive
                )
            ProviderType.RESPONSES ->
                ResponsesLlmClient(
                    baseUrl = profile.baseUrl, apiKey = key, modelName = profile.model,
                    webSearchEnabled = searchActive
                )
            else -> AnthropicLlmClient(
                baseUrl = profile.baseUrl, apiKey = key, modelName = profile.model,
                webSearchEnabled = searchActive,
                webSearchMinimalDefinition = effectiveKind != ProviderKind.ANTHROPIC
            )
        }
        cache[profile.id] = client
        cacheStamp[profile.id] = stamp
        return client
    }

    private fun keyIdentity(key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun invalidate(profileId: String) {
        cache.remove(profileId)
        cacheStamp.remove(profileId)
    }
}
