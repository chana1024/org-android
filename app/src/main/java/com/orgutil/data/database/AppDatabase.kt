package com.orgutil.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.orgutil.data.database.dao.ChatDao
import com.orgutil.data.database.dao.FileDao
import com.orgutil.data.database.entity.ChatAuditLogEntity
import com.orgutil.data.database.entity.ChatMessageEntity
import com.orgutil.data.database.entity.ChatSessionEntity
import com.orgutil.data.database.entity.FileContentEntity
import com.orgutil.data.database.entity.FileContentFtsEntity
import com.orgutil.data.database.entity.FileMetadataEntity

@Database(
    entities = [
        FileMetadataEntity::class,
        FileContentFtsEntity::class,
        FileContentEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        ChatAuditLogEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun chatDao(): ChatDao

    companion object {
        /**
         * v3 -> v4:
         * - Adds `file_content` (plain table, path primary key) holding the
         *   original file body for previews and point lookups.
         * - Rebuilds the FTS index: v3 rows were written with INSERT OR
         *   REPLACE, which appends ghost rows on FTS4 (old content kept
         *   matching after updates). Re-inserting ORDER BY rowid keeps the
         *   newest version per path; the FTS table is then emptied and the
         *   indexer's missing-FTS self-heal re-encodes all content (CJK
         *   bigram encoding) on the next update pass.
         *   FTS content is re-derivable from `file_content`, so no data is
         *   lost by clearing it; the empty index only costs one re-index run.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `file_content` (
                        `path` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        PRIMARY KEY(`path`)
                    )
                    """.trimIndent()
                )
                // Ghost rows mean several rows can share a path; the newest
                // write has the highest rowid, so ORDER BY rowid makes it win.
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `file_content` (`path`, `content`)
                    SELECT `path`, `content` FROM `file_content_fts` ORDER BY rowid
                    """.trimIndent()
                )
                db.execSQL("DELETE FROM `file_content_fts`")
            }
        }

        /**
         * v4 -> v5: agent chat harness tables (sessions, messages, audit log).
         * Pure additions; no existing note-index data is touched.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_session` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `mode` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `autoArmedAt` INTEGER,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_message` (
                        `id` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `toolName` TEXT,
                        `toolArgsJson` TEXT,
                        `toolResultSummary` TEXT,
                        `riskLevel` TEXT,
                        `approvalState` TEXT,
                        `decisionSource` TEXT,
                        `isStreaming` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_message_sessionId` " +
                        "ON `chat_message` (`sessionId`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_audit_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `messageId` TEXT NOT NULL,
                        `ts` INTEGER NOT NULL,
                        `mode` TEXT NOT NULL,
                        `tool` TEXT NOT NULL,
                        `argsDigest` TEXT NOT NULL,
                        `decisionSource` TEXT NOT NULL,
                        `approvalState` TEXT NOT NULL,
                        `result` TEXT NOT NULL,
                        `affectedPaths` TEXT NOT NULL,
                        `bytesWritten` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_audit_log_sessionId` " +
                        "ON `chat_audit_log` (`sessionId`)"
                )
            }
        }
    }
}
