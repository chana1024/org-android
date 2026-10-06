package com.orgutil.domain.chat

import kotlinx.serialization.Serializable

/** A snapshot of an Org heading attached as context to one chat message. */
@Serializable
data class AgendaContextReference(
    val id: String,
    val fileName: String,
    val title: String,
    val todo: String?,
    val hierarchy: String,
    val scheduled: String?,
    val deadline: String?,
    val tags: List<String>,
    /** The selected heading's body and descendants at the time it was sent. */
    val content: String
)
