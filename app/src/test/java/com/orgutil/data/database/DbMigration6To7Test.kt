package com.orgutil.data.database

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Pins FM-S5 (旧库升级破坏数据) against a real SQLite engine: the exact v6
 * schema (as Room generated it, including the 5->6 ALTERed columns) filled
 * with sentinel rows must survive [DbMigrationSql.V6_TO_V7] — same statement
 * list the Room Migration(6,7) object executes, so the test cannot drift
 * from the shipped migration. New columns must carry the defaults the v7
 * entities declare (Room validates defaults after migration).
 */
class DbMigration6To7Test {

    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { stmt ->
            // ---- exact v6 schema ----
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS `file_metadata` (" +
                    "`path` TEXT NOT NULL, `fileName` TEXT NOT NULL, `lastModified` INTEGER NOT NULL, " +
                    "`size` INTEGER NOT NULL, PRIMARY KEY(`path`))"
            )
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS `index_file_metadata_fileName` ON `file_metadata` (`fileName`)"
            )
            stmt.execute(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `file_content_fts` USING FTS4(" +
                    "`path` TEXT NOT NULL, `content` TEXT NOT NULL, tokenize=unicode61)"
            )
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS `file_content` (" +
                    "`path` TEXT NOT NULL, `content` TEXT NOT NULL, PRIMARY KEY(`path`))"
            )
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS `chat_session` (" +
                    "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `mode` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `autoArmedAt` INTEGER, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS `chat_message` (" +
                    "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                    "`content` TEXT NOT NULL, `toolName` TEXT, `toolArgsJson` TEXT, " +
                    "`toolResultSummary` TEXT, `riskLevel` TEXT, `approvalState` TEXT, " +
                    "`decisionSource` TEXT, `isStreaming` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`toolUseId` TEXT, `toolUsesJson` TEXT, PRIMARY KEY(`id`))"
            )
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS `index_chat_message_sessionId` ON `chat_message` (`sessionId`)"
            )
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS `chat_audit_log` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, " +
                    "`messageId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `mode` TEXT NOT NULL, " +
                    "`tool` TEXT NOT NULL, `argsDigest` TEXT NOT NULL, `decisionSource` TEXT NOT NULL, " +
                    "`approvalState` TEXT NOT NULL, `result` TEXT NOT NULL, `affectedPaths` TEXT NOT NULL, " +
                    "`bytesWritten` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL)"
            )
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS `index_chat_audit_log_sessionId` ON `chat_audit_log` (`sessionId`)"
            )

            // ---- sentinel user data ----
            stmt.execute(
                "INSERT INTO file_metadata(path, fileName, lastModified, size) " +
                    "VALUES('tree/notes/gtd.org', 'gtd.org', 111, 42)"
            )
            stmt.execute("INSERT INTO file_content(path, content) VALUES('tree/notes/gtd.org', 'sentinel body')")
            stmt.execute("INSERT INTO file_content_fts(path, content) VALUES('tree/notes/gtd.org', 'sentinel body')")
            stmt.execute(
                "INSERT INTO chat_session(id, title, mode, createdAt, autoArmedAt, updatedAt) " +
                    "VALUES('s1', 'Chat', 'APPROVAL', 100, NULL, 200)"
            )
            stmt.execute(
                "INSERT INTO chat_message(id, sessionId, role, content, toolName, toolArgsJson, " +
                    "toolResultSummary, riskLevel, approvalState, decisionSource, isStreaming, createdAt, " +
                    "toolUseId, toolUsesJson) " +
                    "VALUES('m1', 's1', 'user', 'hello', NULL, NULL, NULL, NULL, NULL, NULL, 0, 101, NULL, NULL)"
            )
            stmt.execute(
                "INSERT INTO chat_audit_log(sessionId, messageId, ts, mode, tool, argsDigest, " +
                    "decisionSource, approvalState, result, affectedPaths, bytesWritten, durationMs) " +
                    "VALUES('s1', 'm1', 102, 'APPROVAL', 'org_read_file', 'digest', " +
                    "'AUTO_POLICY', 'APPROVED', 'OK', '', 0, 5)"
            )
        }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    private fun columnNames(table: String): Set<String> {
        val columns = mutableSetOf<String>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) columns.add(rs.getString(2))
            }
        }
        return columns
    }

    private fun queryScalar(sql: String): Any? =
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                if (!rs.next()) null
                // sqlite-jdbc returns Integer for COUNT(*) and some numerics.
                else when (val v = rs.getObject(1)) {
                    is Int -> v.toLong()
                    else -> v
                }
            }
        }

    @Test
    fun `migration preserves every v6 row and adds v7 columns and tables`() {
        connection.createStatement().use { stmt ->
            DbMigrationSql.V6_TO_V7.forEach(stmt::execute)
        }

        // Old data intact.
        assertEquals("tree/notes/gtd.org", queryScalar("SELECT path FROM file_metadata LIMIT 1"))
        assertEquals("sentinel body", queryScalar("SELECT content FROM file_content LIMIT 1"))
        assertEquals("hello", queryScalar("SELECT content FROM chat_message LIMIT 1"))
        assertEquals(1L, queryScalar("SELECT COUNT(*) FROM chat_audit_log"))
        assertEquals("Chat", queryScalar("SELECT title FROM chat_session LIMIT 1"))

        // New chat_session columns with entity-declared defaults.
        val sessionColumns = columnNames("chat_session")
        assertTrue(sessionColumns.containsAll(setOf("profileId", "runStatus", "lastPrompt")))
        assertEquals("IDLE", queryScalar("SELECT runStatus FROM chat_session WHERE id = 's1'"))
        assertEquals(null, queryScalar("SELECT profileId FROM chat_session WHERE id = 's1'"))
        assertEquals(null, queryScalar("SELECT lastPrompt FROM chat_session WHERE id = 's1'"))

        // New tables exist with the v7 entity shape.
        assertTrue(columnNames("chat_compaction_summary").containsAll(
            setOf("id", "sessionId", "summary", "upToCreatedAt", "createdAt")
        ))
        assertTrue(columnNames("llm_provider_profile").containsAll(
            setOf("id", "name", "type", "baseUrl", "model", "isDefault", "createdAt", "updatedAt")
        ))
        assertEquals(
            1L,
            queryScalar(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_chat_compaction_summary_sessionId'"
            )
        )
        // isDefault must be NOT NULL (entity is non-null Boolean).
        assertEquals(
            1L,
            queryScalar(
                "SELECT COUNT(*) FROM pragma_table_info('llm_provider_profile') " +
                    "WHERE name = 'isDefault' AND `notnull` = 1"
            )
        )
    }

    @Test
    fun `migration is idempotent-safe on fresh v7 tables`() {
        connection.createStatement().use { stmt ->
            DbMigrationSql.V6_TO_V7.forEach(stmt::execute)
        }
        // ALTERs cannot re-run; tables use IF NOT EXISTS. Re-running only the
        // CREATE statements must not fail (guards partial-failure recovery).
        connection.createStatement().use { stmt ->
            DbMigrationSql.V6_TO_V7
                .filter { it.trim().startsWith("CREATE", ignoreCase = true) }
                .forEach(stmt::execute)
        }
        assertFalse(columnNames("chat_compaction_summary").isEmpty())
    }
}
