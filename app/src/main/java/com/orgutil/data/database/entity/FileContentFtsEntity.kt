package com.orgutil.data.database.entity

import androidx.room.Entity
import androidx.room.Fts4

@Fts4(tokenizer = "unicode61")
@Entity(tableName = "file_content_fts")
data class FileContentFtsEntity(
    val path: String,
    val content: String
)