package com.orgutil.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "file_metadata",
    indices = [Index(value = ["fileName"])]
)
data class FileMetadataEntity(
    @PrimaryKey
    val path: String,
    val fileName: String,
    val lastModified: Long,
    val size: Long
)
