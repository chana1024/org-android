package com.orgutil.domain.chat.skills

import kotlinx.serialization.Serializable

/**
 * One trusted source item captured at /org send time by a verified fresh
 * read. This is the provenance the integration tool later re-verifies
 * before touching anything: canonical relative path (fileName alone is not
 * unique), heading identity, the RAW subtree text (byte-exact, including
 * drawers/properties/descendants - never UI-rendered text) and the file
 * content hash at capture time.
 */
@Serializable
data class OrgSourceSnapshot(
    /** Reference id as shown to the model: "URI#sourceOffset". */
    val referenceId: String,
    /** Canonical path relative to the notes tree root, e.g. "gtd/inbox.org". */
    val relativePath: String,
    val uri: String,
    val title: String,
    val todo: String? = null,
    /** Heading path for provenance, e.g. "Inbox › Fix login bug". */
    val hierarchy: String = "",
    val headingLevel: Int,
    /** Offset of the heading's first star in the file content at capture. */
    val headingOffset: Int,
    /** Raw subtree text (heading line through just before the next heading of level <= this one). */
    val subtreeText: String,
    /** sha256 (hex) of the WHOLE file content at capture. */
    val fileSha256: String,
    val fileLength: Int
)

/**
 * The resolved skill invocation persisted inside the user message content
 * envelope. Persisting id/version/instruction text means resume, retry and
 * conversation trimming all re-derive the exact contract from the
 * transcript itself - there is no in-memory "active skill" that could leak
 * across sessions or runs.
 */
@Serializable
data class SkillSelection(
    val skillId: String,
    val skillVersion: Int,
    val instructions: String,
    /** User prose written after the command (may be blank). */
    val arguments: String = "",
    /** Verified source snapshots captured at send time (empty for skills without references). */
    val sources: List<OrgSourceSnapshot> = emptyList()
)
