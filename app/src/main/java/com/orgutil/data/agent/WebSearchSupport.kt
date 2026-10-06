package com.orgutil.data.agent

/**
 * Honest per-route answer to "can this profile do provider-native web
 * search?". Derived from the OFFICIAL docs of each provider x protocol pair
 * (see implementation-report), never from protocol shape alone.
 *
 * - SUPPORTED: the provider documents hosted search on this wire protocol.
 * - UNVERIFIED: the endpoint speaks the protocol, but hosted search is not
 *   documented for it (e.g. GLM's Anthropic-compatible layer). The user may
 *   still force the search tool via an explicit override for custom
 *   gateways - we never claim it works, we just send it and report what
 *   comes back.
 * - UNSUPPORTED: the provider documents that it ignores the search tool on
 *   this route (DeepSeek Responses). No adapter is installed; the override
 *   cannot change what the server discards.
 */
sealed class WebSearchSupport {
    abstract val reason: String

    data class Supported(override val reason: String) : WebSearchSupport()
    data class Unverified(override val reason: String) : WebSearchSupport()
    data class Unsupported(override val reason: String) : WebSearchSupport()

    val label: String
        get() = when (this) {
            is Supported -> "支持"
            is Unverified -> "未验证"
            is Unsupported -> "不支持"
        }

    companion object {
        /**
         * @param overrideValue explicit user override from the profile
         * ("supported" sends the search tool even on unverified routes;
         * it can never turn a documented-ignored route into a working one).
         */
        fun resolve(
            protocol: ProviderType,
            baseUrl: String,
            kindOverride: ProviderKind,
            overrideValue: String?
        ): WebSearchSupport {
            val kind = kindOverride.effective(baseUrl)
            val forced = overrideValue == OVERRIDE_SUPPORTED
            return when (protocol) {
                ProviderType.ANTHROPIC -> when (kind) {
                    ProviderKind.ANTHROPIC -> Supported("Anthropic 官方托管搜索（web_search 工具）")
                    ProviderKind.DEEPSEEK -> Supported("DeepSeek Anthropic 接口支持 server_tool_use / web_search_tool_result")
                    else -> if (forced) {
                        Unverified("该入口未文档化托管搜索，已按你的强制设置发送搜索工具，成败以实际返回为准")
                    } else {
                        Unverified(
                            if (kind == ProviderKind.GLM)
                                "智谱 Claude 兼容入口官方未文档化托管搜索；如确认可用请打开强制开关"
                            else "自定义入口的托管搜索能力未知；如确认可用请打开强制开关"
                        )
                    }
                }
                ProviderType.OPENAI_COMPATIBLE -> when (kind) {
                    ProviderKind.GLM -> Supported("GLM 对话补全 web_search 配置工具")
                    else -> if (forced) {
                        Unverified("非智谱入口按 GLM 形态发送 web_search 配置，成败以实际返回为准")
                    } else {
                        Unverified(
                            "Chat Completions 的托管搜索为智谱专有形态；其他入口请改用 Responses 或 Anthropic 协议" +
                                (if (kind == ProviderKind.DEEPSEEK) "（DeepSeek 搜索走 api.deepseek.com/anthropic）" else "")
                        )
                    }
                }
                ProviderType.RESPONSES -> when (kind) {
                    ProviderKind.OPENAI -> Supported("OpenAI Responses 托管搜索（tools: web_search）")
                    ProviderKind.GLM -> Supported("GLM Responses 原生托管搜索（基址 open.bigmodel.cn/api/v1）")
                    // DeepSeek 文档明确：Responses 忽略 web_search 工具。
                    ProviderKind.DEEPSEEK -> Unsupported("DeepSeek Responses 官方文档明确忽略 web_search 工具；请改用 api.deepseek.com/anthropic")
                    else -> if (forced) {
                        Unverified("自定义 Responses 入口按 {type:\"web_search\"} 发送，成败以实际返回为准")
                    } else {
                        Unverified("自定义 Responses 入口的托管搜索能力未知；如确认可用请打开强制开关")
                    }
                }
            }
        }

        const val OVERRIDE_SUPPORTED = "supported"
    }
}
