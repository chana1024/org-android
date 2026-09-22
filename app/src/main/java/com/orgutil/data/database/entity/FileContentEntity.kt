package com.orgutil.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Plain (non-FTS) copy of every indexed file body, keyed by document URI.
 *
 * This is the read path for search previews and any other "show me the
 * content" lookup: it is a normal table, so `WHERE path = ?` is an index
 * seek (the FTS4 table has no index on `path` and must not be used for
 * point lookups).
 *
 * [FileContentFtsEntity] holds the CJK-encoded search form of the same
 * content; the two tables are always written together in one transaction.
 */
@Entity(tableName = "file_content")
data class FileContentEntity(
    @PrimaryKey val path: String,
    val content: String
)
