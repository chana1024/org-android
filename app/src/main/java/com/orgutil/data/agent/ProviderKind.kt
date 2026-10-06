package com.orgutil.data.agent

/**
 * Which provider a profile actually talks to, inferred from the base URL
 * host (or pinned explicitly on the profile). Search support differs per
 * PROVIDER x PROTOCOL pair, not per protocol alone - the honest capability
 * answer in [WebSearchSupport] needs this identity.
 *
 * [AUTO] means "detect from the URL"; it resolves at capability-check time
 * and stays GENERIC for unknown hosts (self-hosted gateways), where search
 * support is genuinely unknown until the user says otherwise.
 */
enum class ProviderKind(val label: String) {
    AUTO("自动（按地址判断）"),
    ANTHROPIC("Anthropic"),
    DEEPSEEK("DeepSeek"),
    GLM("智谱 GLM"),
    OPENAI("OpenAI"),
    GENERIC("自定义/其他");

    /** Effective concrete kind: AUTO resolves from [baseUrl], never to AUTO. */
    fun effective(baseUrl: String): ProviderKind {
        if (this != AUTO) return this
        return fromUrl(baseUrl)
    }

    companion object {
        fun fromUrl(baseUrl: String): ProviderKind {
            val host = baseUrl.trim().removePrefix("https://").removePrefix("http://")
                .substringBefore('/').lowercase()
            return when {
                host == "api.anthropic.com" || host.endsWith(".anthropic.com") -> ANTHROPIC
                host == "api.deepseek.com" || host.endsWith(".deepseek.com") -> DEEPSEEK
                host == "open.bigmodel.cn" || host.endsWith(".bigmodel.cn") -> GLM
                host == "api.openai.com" || host.endsWith(".openai.com") -> OPENAI
                else -> GENERIC
            }
        }
    }
}
