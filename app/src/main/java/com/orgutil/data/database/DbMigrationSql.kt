package com.orgutil.data.database

/**
 * Single source of truth for the v6 -> v7 SQL. The Room [Migration] object
 * executes exactly this list, and the JVM migration test replays the same
 * list against sqlite-jdbc, so the shipped migration cannot drift from the
 * tested one.
 *
 * v6 -> v7:
 * - `chat_session.profileId`: per-session provider profile (NULL = default).
 * - `chat_session.runStatus`: IDLE / RUNNING / AWAITING_APPROVAL / INTERRUPTED
 *   so a killed run is detectable and resumable after restart.
 * - `chat_session.lastPrompt`: the prompt of the in-flight run (resume hint).
 * - `chat_compaction_summary`: persisted context-compaction summaries; the
 *   original chat_message history is never modified.
 * - `llm_provider_profile`: named provider configs (no secrets; API keys
 *   live in the encrypted credential store keyed by profile id).
 */
object DbMigrationSql {
    val V6_TO_V7: List<String> = listOf(
        "ALTER TABLE `chat_session` ADD COLUMN `profileId` TEXT",
        "ALTER TABLE `chat_session` ADD COLUMN `runStatus` TEXT NOT NULL DEFAULT 'IDLE'",
        "ALTER TABLE `chat_session` ADD COLUMN `lastPrompt` TEXT",
        """
        CREATE TABLE IF NOT EXISTS `chat_compaction_summary` (
            `id` TEXT NOT NULL,
            `sessionId` TEXT NOT NULL,
            `summary` TEXT NOT NULL,
            `upToCreatedAt` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_chat_compaction_summary_sessionId` " +
            "ON `chat_compaction_summary` (`sessionId`)",
        """
        CREATE TABLE IF NOT EXISTS `llm_provider_profile` (
            `id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `type` TEXT NOT NULL,
            `baseUrl` TEXT NOT NULL,
            `model` TEXT NOT NULL,
            `isDefault` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent()
    )

    // v7 -> v8: provider-native web search.
    // - `chat_message.nativeBlocksJson`: assistant turns that contained
    //   HOSTED (server-side) search persist the provider's content blocks /
    //   output items verbatim (encrypted_content included) so the next turn
    //   can replay them exactly. Nullable; ordinary turns stay null.
    // - `llm_provider_profile.providerKind`: which provider the profile
    //   targets (NULL = auto-detect from the base URL) - search support is
    //   resolved per provider x protocol, honestly.
    // - `llm_provider_profile.webSearchEnabled`: per-profile hosted-search
    //   switch (default off; existing profiles keep their behavior).
    // - `llm_provider_profile.searchSupportOverride`: explicit user
    //   assertion that an undocumented custom endpoint accepts the search
    //   tool. NULL = no override.
    val V7_TO_V8: List<String> = listOf(
        "ALTER TABLE `chat_message` ADD COLUMN `nativeBlocksJson` TEXT",
        "ALTER TABLE `llm_provider_profile` ADD COLUMN `providerKind` TEXT",
        "ALTER TABLE `llm_provider_profile` ADD COLUMN `webSearchEnabled` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `llm_provider_profile` ADD COLUMN `searchSupportOverride` TEXT"
    )
}
