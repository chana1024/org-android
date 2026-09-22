package com.orgutil.data.database.entity

/**
 * Projection row for full-text search: metadata joined with the original
 * file body in a single query (previews must not issue per-hit content
 * lookups).
 */
data class FileSearchResult(
    val path: String,
    val fileName: String,
    val lastModified: Long,
    val size: Long,
    val content: String
)
