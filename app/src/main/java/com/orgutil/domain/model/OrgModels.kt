package com.orgutil.domain.model

import android.net.Uri

data class OrgDocument(
    val uri: Uri,
    val fileName: String,
    val content: String,
    val lastModified: Long,
    val nodes: List<OrgNode>,
    val preamble: String = ""
)

data class OrgNode(
    val level: Int,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val children: List<OrgNode> = emptyList(),
    val todo: String? = null,
    val priority: String? = null,
    /**
     * Source identity of this heading in the document text: offset of the
     * first star. Only headings whose stars/title/keyword could be verified
     * against the raw text carry a real offset; everything else stays at -1
     * and gets no per-heading source actions (e.g. the habit chip).
     */
    val sourceOffset: Int = -1,
    /** Offset of the title's first character; -1 when unverifiable. */
    val titleOffset: Int = -1,
    /** Own :PROPERTIES: drawer carries STYLE=habit (independent of repeater). */
    val isHabitStyle: Boolean = false,
    /** Own SCHEDULED timestamp has a repeater (+1d, .+2w, ++1m, ...). */
    val hasRepeatingScheduled: Boolean = false
)

data class OrgFileInfo(
    val uri: Uri,
    val name: String,
    val lastModified: Long,
    val size: Long,
    val isFavorite: Boolean = false,
    val isDirectory: Boolean = false,
    val searchPreview: String? = null,
    val searchPreviewMatchStart: Int? = null,
    val searchPreviewMatchLength: Int? = null,
    val searchMatchContentOffset: Int? = null
)
