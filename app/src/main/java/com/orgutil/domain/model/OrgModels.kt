package com.orgutil.domain.model

import android.net.Uri

data class OrgDocument(
    val uri: Uri,
    val fileName: String,
    val content: String,
    val lastModified: Long,
    val nodes: List<OrgNode>
)

data class OrgNode(
    val level: Int,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val children: List<OrgNode> = emptyList(),
    val todo: String? = null,
    val priority: String? = null
)

data class OrgFileInfo(
    val uri: Uri,
    val name: String,
    val lastModified: Long,
    val size: Long
)