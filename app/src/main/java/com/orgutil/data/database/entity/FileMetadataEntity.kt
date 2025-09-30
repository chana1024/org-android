package com.orgutil.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "file_metadata")
data class FileMetadataEntity(
    @PrimaryKey
    val path: String,
    val fileName: String,
    val lastModified: Long,
    val size: Long
)
