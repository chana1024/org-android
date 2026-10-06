package com.orgutil.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orgutil.data.agent.AgentPathResolver
import com.orgutil.data.datasource.DocumentTreeStore
import com.orgutil.domain.chat.AgendaContextReference
import com.orgutil.domain.chat.skills.OrgSourceSnapshot
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.model.OrgNode
import com.orgutil.domain.repository.OrgFileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A brand-new destination note. */
data class OrgDestinationCreate(val relativePath: String, val content: String)

/** An existing destination note; [baseSha256] must match the file at execution time. */
data class OrgDestinationUpdate(
    val relativePath: String,
    val content: String,
    val baseSha256: String
)

data class OrgIntegrationRequest(
    val sessionId: String,
    val trustedSources: List<OrgSourceSnapshot>,
    val referenceIds: List<String>,
    val creates: List<OrgDestinationCreate> = emptyList(),
    val updates: List<OrgDestinationUpdate> = emptyList()
)

/** Honest per-item outcome so the tool can report partial work truthfully. */
data class OrgSourceOutcome(
    val referenceId: String,
    val relativePath: String,
    val cleaned: Boolean,
    val reason: String? = null
)

data class OrgIntegrationOutcome(
    val journalId: String,
    val createdPaths: List<String>,
    val updatedPaths: List<String>,
    val cleanedSources: List<OrgSourceOutcome>,
    val rolledBack: Boolean,
    val message: String
) {
    val cleanedCount: Int get() = cleanedSources.count { it.cleaned }
}

/** Thrown for every rejection below; the message is safe to show the model and the user. */
class OrgIntegrationReject(message: String) : IllegalStateException(message)

/**
 * One mutation intent, persisted BEFORE the write is attempted and updated
 * after it verifies. A crash mid-write leaves an unverified attempt behind,
 * which is exactly what makes the recovery state honest.
 */
@Serializable
data class JournalWriteAttempt(
    /** "create" | "update" | "cleanup". */
    val phase: String,
    val path: String,
    /** Empty until the created document's URI is known. */
    val uri: String,
    /** sha256 of the file content before this write; null for creates. */
    val beforeSha256: String? = null,
    /** sha256 of the content this write attempted. */
    val attemptedSha256: String,
    val verified: Boolean = false
)

/** Durable journal entry; one per integration attempt, app-private. */
@Serializable
data class OrgIntegrationJournal(
    val id: String,
    val sessionId: String,
    val createdAt: Long,
    /**
     * PLANNED (backups only, nothing mutated) -> MUTATING (intent persisted,
     * mutations in flight) -> DESTINATIONS_WRITTEN -> COMPLETED.
     * Terminal failures: ROLLED_BACK (every mutation verified restored) or
     * RECOVERY_REQUIRED (something is uncertain - manual recovery needed).
     */
    val state: String,
    /**
     * Stable source identity: "path SUBTREE-SHA256". Overlap (not equality)
     * of these keys against a new request decides replay blocking, so the
     * same text in two different files never collides and a partially
     * overlapping retry is still refused.
     */
    val sourceIdentities: List<String> = emptyList(),
    val referenceIds: List<String>,
    val sourcePaths: List<String>,
    val createdPaths: List<String> = emptyList(),
    val updatedPaths: List<String> = emptyList(),
    /** Cleanups whose write completed AND verified. */
    val cleanedReferenceIds: List<String> = emptyList(),
    /** Cleanups attempted but never verified (crash window) - state unknown. */
    val possiblyCleanedReferenceIds: List<String> = emptyList(),
    val writes: List<JournalWriteAttempt> = emptyList(),
    val error: String? = null,
    val backupDir: String? = null
)

/**
 * The /org integration engine. Two entry points:
 *
 * 1. [captureSources] - the verified fresh read at /org send time. Each
 *    selected Agenda reference is re-read from the repository, the heading
 *    is re-located by offset AND verified against the title/todo the user
 *    selected (an offset alone could hit a different item after edits), and
 *    the RAW subtree (heading line through the byte before the next heading
 *    of level <= its own) is snapshotted together with the whole-file
 *    sha256 and the canonical relative path. Overlapping selections are
 *    normalized here: a source nested inside another selected source of the
 *    same file is dropped so its subtree is never integrated or deleted
 *    twice.
 *
 * 2. [integrate] - the single coordinated mutation executed by the
 *    org_integrate agent tool. Ordering and safety mirror GtdArchiveService
 *    plus an explicit crash-safe journal:
 *
 *    a. Validate everything (trusted references, path confinement, stale
 *       destination hashes, nonempty content, unambiguous sources) BEFORE
 *       any write. Destinations are hash-re-checked AGAIN immediately before
 *       each replacement.
 *    b. Back up every file that can change to app-private storage and
 *       journal the attempt (PLANNED) with atomic file replacement.
 *    c. Flip the journal to MUTATING (persisted) BEFORE the first mutation,
 *       then checkpoint every single write intent (path, uri, phase,
 *       before/attempted hashes) BEFORE attempting it and mark it verified
 *       only after the repository's read-back-verified write returned. A
 *       crash can therefore never leave a mutated file behind an
 *       innocent-looking journal.
 *    d. Destinations first (duplicate, never lose), then cleanup LAST, per
 *       source file re-read and boundary-verified immediately before its
 *       write. Cleanup matches only occurrences that sit on real heading
 *       boundaries (line start + heading line at the end), never a quoted
 *       copy inside another node's body.
 *    e. On ANY failure (including cancellation) a NonCancellable rollback
 *       runs conservatively: it re-reads each touched file and restores it
 *       ONLY when the live content is provably this attempt's own write;
 *       concurrent edits are never overwritten or deleted. Files that
 *       cannot be safely auto-restored leave the journal in
 *       RECOVERY_REQUIRED with the exact paths and the backup directory -
 *       ROLLED_BACK is claimed only when every restoration verified.
 *
 *    [integrateMutex] serializes integrations started by THIS service
 *    only. It is not an atomicity guarantee against other app components
 *    or external editors: those are detected (stale hashes, unexpected live
 *    content) and refused/recovered, never prevented.
 *
 *    Same-file integration is deterministic, never guessed: the submitted
 *    content must already EXCLUDE the source's original occurrence (the
 *    merged copy may differ, e.g. re-leveled). If an original-boundary
 *    occurrence is still present, the content is rejected - a byte-identical
 *    duplicate cannot be told apart from the original, so we refuse rather
 *    than pick.
 */
@Singleton
class OrgIntegrationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentTreeStore: DocumentTreeStore,
    private val orgFileRepository: OrgFileRepository,
    private val pathResolver: AgentPathResolver
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val jsonLenient = Json { ignoreUnknownKeys = true }

    /** Serializes integrations started by this service only (see class doc). */
    private val integrateMutex = Mutex()

    /** In-memory mirror of one journaled write attempt, holding full contents. */
    private data class AttemptRecord(
        val phase: String,
        val path: String,
        val uri: Uri,
        /** File content before this write; null for created files. */
        val beforeContent: String?,
        val attemptedContent: String
    )

    // ------------------------------------------------------------------
    // 1. Send-time capture
    // ------------------------------------------------------------------

    /**
     * Verified fresh read of the selected references at send time. The
     * [references] are the chips the user attached (id = "URI#sourceOffset"
     * plus the title/todo captured when the chip was picked); each one is
     * re-resolved against the live file, and a mismatch rejects the send
     * with the draft and attachments preserved.
     */
    suspend fun captureSources(
        references: List<AgendaContextReference>
    ): Result<List<OrgSourceSnapshot>> = withContext(Dispatchers.IO) {
        runCatching {
            require(references.isNotEmpty()) { "没有选中任何 Agenda 条目" }
            val snapshots = references.map { captureOne(it) }
            normalizeOverlaps(snapshots.distinctBy { it.referenceId })
        }
    }

    private suspend fun captureOne(reference: AgendaContextReference): OrgSourceSnapshot {
        val uriString = reference.id.substringBeforeLast('#')
        val offset = reference.id.substringAfterLast('#').toIntOrNull()
            ?: throw OrgIntegrationReject("引用 id 无效：${reference.id}")
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: throw OrgIntegrationReject("引用 id 无效：${reference.id}")
        val document = orgFileRepository.readOrgFile(uri).getOrElse { error ->
            throw OrgIntegrationReject("读取引用文件失败：${error.message}")
        }
        val node = document.nodes.firstNotNullOfOrNull { it.findBySourceOffset(offset) }
            ?: throw OrgIntegrationReject(
                "「${reference.title}」所在位置已变化（找不到偏移 $offset 的标题），请重新选择引用"
            )
        // Offset alone is not identity: the file may have shifted since the
        // chip was picked, so the heading must still be the selected item.
        if (node.title != reference.title || node.todo != reference.todo) {
            throw OrgIntegrationReject(
                "「${reference.title}」已变化（现在是「${node.title}」），请重新选择引用"
            )
        }
        val (start, end) = subtreeRange(document.content, node.sourceOffset, node.level)
        val rawSubtree = document.content.substring(start, end)
        if (rawSubtree.isBlank()) {
            throw OrgIntegrationReject("「${node.title}」的子树内容为空")
        }
        return OrgSourceSnapshot(
            referenceId = reference.id,
            relativePath = canonicalRelativePath(uri),
            uri = uri.toString(),
            title = node.title,
            todo = node.todo,
            hierarchy = reference.hierarchy,
            headingLevel = node.level,
            headingOffset = node.sourceOffset,
            subtreeText = rawSubtree,
            fileSha256 = sha256(document.content),
            fileLength = document.content.length
        )
    }

    /** Drops nested selections: the outer subtree wins, children ride along verbatim. */
    private fun normalizeOverlaps(snapshots: List<OrgSourceSnapshot>): List<OrgSourceSnapshot> {
        val byFile = snapshots.groupBy { it.relativePath }
        val kept = mutableListOf<OrgSourceSnapshot>()
        for ((_, fileSnapshots) in byFile) {
            val ordered = fileSnapshots.sortedBy { it.headingOffset }
            var coveredUntil = -1
            for (candidate in ordered) {
                if (candidate.headingOffset < coveredUntil) continue // nested in a kept source
                kept += candidate
                coveredUntil = candidate.headingOffset + candidate.subtreeText.length
            }
        }
        return kept.sortedBy { it.referenceId }
    }

    // ------------------------------------------------------------------
    // 2. Coordinated integration
    // ------------------------------------------------------------------

    suspend fun integrate(request: OrgIntegrationRequest): OrgIntegrationOutcome = integrateMutex.withLock {
        withContext(Dispatchers.IO) {
            executeIntegration(request)
        }
    }

    private suspend fun executeIntegration(request: OrgIntegrationRequest): OrgIntegrationOutcome {
        // ---- validation: trusted references only (nothing mutated yet) ----
        if (request.referenceIds.isEmpty()) {
            throw OrgIntegrationReject("reference_ids is empty - pass at least one trusted reference id.")
        }
        val trustedById = request.trustedSources.associateBy { it.referenceId }
        val unknown = request.referenceIds.distinct().filter { it !in trustedById }
        if (unknown.isNotEmpty()) {
            throw OrgIntegrationReject(
                "Untrusted reference id(s): ${unknown.joinToString(", ")}. " +
                    "Only reference ids attached to this /org run may be integrated."
            )
        }
        val selected = normalizeOverlaps(request.referenceIds.distinct().map { trustedById.getValue(it) })

        if (request.creates.isEmpty() && request.updates.isEmpty()) {
            throw OrgIntegrationReject(
                "No destinations given. Pass creates and/or updates with the final note contents."
            )
        }

        // ---- validation: destination paths & contents --------------------
        val creates = request.creates.map { dest ->
            pathResolver.normalizeAndValidate(dest.relativePath, requireOrgExtension = false)
            requireMeaningful(dest.content, dest.relativePath)
            dest.copy(relativePath = dest.relativePath.trim().trimStart('/'))
        }
        val updates = request.updates.map { dest ->
            pathResolver.normalizeAndValidate(dest.relativePath, requireOrgExtension = false)
            requireMeaningful(dest.content, dest.relativePath)
            dest.copy(
                relativePath = dest.relativePath.trim().trimStart('/'),
                baseSha256 = dest.baseSha256.trim().lowercase()
            )
        }
        val duplicateDestinations = (creates.map { it.relativePath } + updates.map { it.relativePath })
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateDestinations.isNotEmpty()) {
            throw OrgIntegrationReject("Duplicate destination path(s): ${duplicateDestinations.joinToString(", ")}.")
        }

        // ---- replay blocking: any unresolved attempt over these sources ---
        checkReplayBlocking(request.sessionId, selected)

        // ---- read + verify current source state (boundary-aware) ---------
        // Sources are resolved first so a stale/ambiguous source aborts
        // before any destination is touched.
        val sourceStates = selected.map { resolveSourceState(it) }
        val sourcesByPath = sourceStates.groupBy { it.snapshot.relativePath }
        val sourcePathsById = selected.associate { it.referenceId to it.relativePath }

        // ---- validation-stage destination state --------------------------
        val createTargets = creates.map { dest ->
            val (parent, finalName) = pathResolver.resolveParentForCreation(dest.relativePath)
            if (parent.findFile(finalName) != null) {
                throw OrgIntegrationReject(
                    "Destination already exists: ${dest.relativePath}. Send it in updates with base_sha256 instead."
                )
            }
            CreateTarget(dest, parent, finalName)
        }
        val updateTargets = updates.map { dest ->
            val file = pathResolver.resolveFile(dest.relativePath)
            val current = readRaw(file.uri)
            val currentHash = sha256(current)
            if (currentHash != dest.baseSha256) {
                throw OrgIntegrationReject(
                    "Stale destination ${dest.relativePath}: the file changed since it was last read " +
                        "(expected sha256 ${dest.baseSha256.take(12)}…, found ${currentHash.take(12)}…). " +
                        "Re-read it, rebuild the full content and retry. Nothing was written."
                )
            }
            // Same-file rule (deterministic): the submitted content must
            // already exclude every selected source occurrence of THIS file
            // on a real heading boundary - a quoted copy inside a body does
            // not count as the original occurrence.
            sourcesByPath[dest.relativePath]?.forEach { state ->
                val occurrences =
                    subtreeBoundaryOccurrences(dest.content, state.subtreeText, state.snapshot.headingLevel).size
                if (occurrences > 0) {
                    throw OrgIntegrationReject(
                        "Same-file integration for ${dest.relativePath} still contains the original " +
                            "subtree of \"${state.snapshot.title.take(50)}\" ($occurrences heading-boundary " +
                            "occurrence(s)). Remove the original occurrence from the submitted content - keep only " +
                            "the merged copy at its new location - or integrate into a different note. " +
                            "Nothing was written."
                    )
                }
            }
            UpdateTarget(dest, file, current, currentHash)
        }

        // ---- journal + backups (before ANY mutation) ---------------------
        val journalId = "${System.currentTimeMillis()}-${request.sessionId.take(8)}"
        val backupDir = File(File(context.filesDir, JOURNAL_DIR), journalId).apply { mkdirs() }
        val originalsToBackup = linkedMapOf<String, Pair<Uri, String>>()
        updateTargets.forEach { originalsToBackup[it.dest.relativePath] = it.file.uri to it.currentContent }
        sourceStates.forEach { state ->
            if (!originalsToBackup.containsKey(state.snapshot.relativePath)) {
                originalsToBackup[state.snapshot.relativePath] =
                    Uri.parse(state.snapshot.uri) to state.currentContent
            }
        }
        originalsToBackup.forEach { (path, pair) -> writeBackup(backupDir, path, pair.second) }
        var journal = OrgIntegrationJournal(
            id = journalId,
            sessionId = request.sessionId,
            createdAt = System.currentTimeMillis(),
            state = "PLANNED",
            sourceIdentities = sourceIdentitiesOf(selected),
            referenceIds = sourceStates.map { it.snapshot.referenceId },
            sourcePaths = sourceStates.map { it.snapshot.relativePath }.distinct(),
            backupDir = backupDir.absolutePath
        )
        writeJournalAtomic(journal)

        // ---- mutations ----------------------------------------------------
        val attempts = mutableListOf<AttemptRecord>()
        val createdPaths = mutableListOf<String>()
        val updatedPaths = mutableListOf<String>()
        val outcomes = mutableListOf<OrgSourceOutcome>()
        try {
            // Persist mutating state BEFORE the first mutation: a crash after
            // a write can never hide behind a PLANNED journal again.
            journal = journal.copy(state = "MUTATING")
            writeJournalAtomic(journal)

            // Destinations first: duplicate, never lose.
            for (target in createTargets) {
                val content = target.dest.content
                // Intent (path level) persisted before the document exists;
                // the URI is amended into the same entry right after creation.
                journal = journal.copy(
                    writes = journal.writes + JournalWriteAttempt(
                        phase = PHASE_CREATE, path = target.dest.relativePath, uri = "",
                        beforeSha256 = null, attemptedSha256 = sha256(content)
                    )
                )
                writeJournalAtomic(journal)
                val created = target.parent.createFile("text/org", target.finalName)
                    ?: throw OrgIntegrationReject("Provider refused to create ${target.dest.relativePath}")
                attempts += AttemptRecord(PHASE_CREATE, target.dest.relativePath, created.uri, null, content)
                journal = journal.copy(writes = journal.writes.mapIndexed { index, write ->
                    if (index == journal.writes.lastIndex) write.copy(uri = created.uri.toString()) else write
                })
                writeJournalAtomic(journal)
                writeVerified(created, target.finalName, content)
                journal = markVerified(journal, attempts)
                createdPaths += target.dest.relativePath
            }
            for (target in updateTargets) {
                // Hash re-check immediately before replacement: the file must
                // still be exactly what the model based its content on.
                val liveNow = readRaw(target.file.uri)
                if (sha256(liveNow) != target.dest.baseSha256) {
                    throw OrgIntegrationReject(
                        "Destination ${target.dest.relativePath} changed after validation " +
                            "(expected sha256 ${target.dest.baseSha256.take(12)}…, found " +
                            "${sha256(liveNow).take(12)}…). Aborting before replacement."
                    )
                }
                attempts += AttemptRecord(
                    PHASE_UPDATE, target.dest.relativePath, target.file.uri,
                    target.currentContent, target.dest.content
                )
                journal = journal.copy(
                    writes = journal.writes + JournalWriteAttempt(
                        phase = PHASE_UPDATE, path = target.dest.relativePath,
                        uri = target.file.uri.toString(),
                        beforeSha256 = target.currentSha256, attemptedSha256 = sha256(target.dest.content)
                    )
                )
                writeJournalAtomic(journal)
                writeVerified(target.file, target.dest.relativePath.substringAfterLast('/'), target.dest.content)
                journal = markVerified(journal, attempts)
                updatedPaths += target.dest.relativePath
            }
            journal = journal.copy(state = "DESTINATIONS_WRITTEN", createdPaths = createdPaths, updatedPaths = updatedPaths)
            writeJournalAtomic(journal)

            // Cleanup LAST, only after every destination edit verified, and
            // per file: re-read + boundary-verify immediately before writing.
            for ((path, states) in sourcesByPath) {
                val uri = Uri.parse(states.first().snapshot.uri)
                if (path in createdPaths || path in updatedPaths) {
                    // Same-file case: the destination write above already
                    // excluded the original occurrence; verify it is really
                    // gone (heading-boundary occurrences only) before
                    // claiming the cleanup.
                    val now = readRaw(uri)
                    outcomes += states.map { state ->
                        if (subtreeBoundaryOccurrences(now, state.subtreeText, state.snapshot.headingLevel).isEmpty()) {
                            OrgSourceOutcome(state.snapshot.referenceId, path, cleaned = true)
                        } else {
                            OrgSourceOutcome(
                                state.snapshot.referenceId, path, cleaned = false,
                                reason = "同文件合并后原条目仍存在，已保留原文待人工处理"
                            )
                        }
                    }
                    continue
                }
                val current = readRaw(uri)
                val resolved = states.map { state ->
                    state to boundaryRange(current, state.subtreeText, state.snapshot.headingLevel)
                }
                val removable = resolved.mapNotNull { (state, range) -> range?.let { state to it } }
                val retainedHere = resolved.filter { it.second == null }
                if (retainedHere.isNotEmpty()) {
                    outcomes += retainedHere.map { (state, _) ->
                        OrgSourceOutcome(
                            state.snapshot.referenceId, path, cleaned = false,
                            reason = "原条目与捕获时不一致或无法唯一定位（可能新增了子节点或出现重复），保留原文"
                        )
                    }
                }
                if (removable.isNotEmpty()) {
                    val cleanedContent = removeRanges(current, removable.map { it.second })
                    val ids = removable.map { it.first.snapshot.referenceId }
                    // "Possibly cleaned" is journaled BEFORE the write: a
                    // crash mid-write leaves an honest unknown, not a claim.
                    journal = journal.copy(
                        possiblyCleanedReferenceIds =
                        (journal.possiblyCleanedReferenceIds + ids).distinct()
                    )
                    writeJournalAtomic(journal)
                    attempts += AttemptRecord(PHASE_CLEANUP, path, uri, current, cleanedContent)
                    journal = journal.copy(
                        writes = journal.writes + JournalWriteAttempt(
                            phase = PHASE_CLEANUP, path = path, uri = uri.toString(),
                            beforeSha256 = sha256(current), attemptedSha256 = sha256(cleanedContent)
                        )
                    )
                    writeJournalAtomic(journal)
                    writeVerified(uriToDocumentFile(uri), path.substringAfterLast('/'), cleanedContent)
                    journal = journal.copy(
                        cleanedReferenceIds = (journal.cleanedReferenceIds + ids).distinct(),
                        possiblyCleanedReferenceIds =
                            journal.possiblyCleanedReferenceIds - ids.toSet()
                    )
                    writeJournalAtomic(journal)
                    outcomes += removable.map { (state, _) ->
                        OrgSourceOutcome(state.snapshot.referenceId, path, cleaned = true)
                    }
                }
            }
            journal = journal.copy(
                state = "COMPLETED",
                cleanedReferenceIds = outcomes.filter { it.cleaned }.map { it.referenceId },
                possiblyCleanedReferenceIds = emptyList()
            )
            writeJournalAtomic(journal)
            return OrgIntegrationOutcome(
                journalId = journalId,
                createdPaths = createdPaths,
                updatedPaths = updatedPaths,
                cleanedSources = outcomes,
                rolledBack = false,
                message = buildSummary(createdPaths, updatedPaths, outcomes)
            )
        } catch (e: Exception) {
            // Conservative recovery. Runs to completion even when the calling
            // coroutine is cancelled, and reports per-source state honestly;
            // a cancellation is re-thrown afterwards so loop semantics hold.
            val summary = recover(journal, attempts, backupDir, e, sourcePathsById)
            if (e is CancellationException) throw e
            throw OrgIntegrationReject(summary)
        }
    }

    // ------------------------------------------------------------------
    // recovery
    // ------------------------------------------------------------------

    /**
     * NonCancellable, conservative rollback. Every touched file is re-read;
     * it is restored ONLY when its live content is provably this attempt's
     * own write (or already the original). Anything else - a concurrent
     * edit, a vanished file, a failed read-back, a refused delete - becomes
     * RECOVERY_REQUIRED with the exact path; the original stays in the
     * backup directory. ROLLED_BACK is claimed only when every restoration
     * verified by read-back.
     */
    private suspend fun recover(
        journalAtFailure: OrgIntegrationJournal,
        attempts: List<AttemptRecord>,
        backupDir: File,
        cause: Throwable,
        sourcePathsById: Map<String, String>
    ): String = withContext(NonCancellable) {
        var journal = journalAtFailure
        val problems = mutableListOf<String>()
        val restored = mutableListOf<String>()

        // Intents whose document URI was never learned (crash inside
        // SAF createFile) have no attempt record; their state is unknown,
        // so they force RECOVERY_REQUIRED instead of a clean claim.
        journal.writes
            .filter { !it.verified && it.uri.isEmpty() }
            .forEach { intent ->
                problems += "creation of ${intent.path} was attempted but its outcome is unknown " +
                    "(check whether the file now exists; it did not exist before this run)"
            }

        for (attempt in attempts.asReversed()) {
            // A read failure is ambiguous (missing file OR broken provider):
            // absence is confirmed ONLY through an explicit existence check,
            // never inferred from an exception.
            val liveContent: String? = try {
                readRaw(attempt.uri)
            } catch (_: Exception) {
                null
            }
            val readFailed = liveContent == null
            val confirmedAbsent = if (readFailed) confirmedAbsent(attempt.uri) else false
            if (attempt.phase == PHASE_CREATE) {
                when {
                    !readFailed && liveContent == attempt.attemptedContent -> {
                        val document = try {
                            uriToDocumentFile(attempt.uri)
                        } catch (_: Exception) {
                            null
                        }
                        val deleted = document?.delete() == true
                        when {
                            !deleted -> problems +=
                                "created ${attempt.path} could not be deleted (kept; it did not exist before this run)"
                            confirmedAbsent(attempt.uri) == true -> restored += attempt.path
                            else -> problems +=
                                "created ${attempt.path} was deleted but its absence could not be confirmed " +
                                    "(verify manually; it did not exist before this run)"
                        }
                    }
                    !readFailed -> problems +=
                        "created ${attempt.path} holds unexpected content (left untouched; it did not exist " +
                            "before this run)"
                    confirmedAbsent == true -> restored += attempt.path // provably never landed / already gone
                    else -> problems +=
                        "created ${attempt.path} could not be inspected during recovery (unknown state; it " +
                            "did not exist before this run)"
                }
            } else {
                val before = attempt.beforeContent
                    ?: error("attempt ${attempt.path} is not a create but has no before-content")
                when {
                    !readFailed && liveContent == before -> restored += attempt.path // untouched or already original
                    !readFailed && liveContent == attempt.attemptedContent -> {
                        try {
                            writeVerified(uriToDocumentFile(attempt.uri), "restore", before)
                            val check = readRaw(attempt.uri)
                            if (check == before) restored += attempt.path
                            else problems += "restore of ${attempt.path} failed read-back verification"
                        } catch (restoreError: Exception) {
                            problems += "restore of ${attempt.path} failed: ${restoreError.message}"
                        }
                    }
                    !readFailed -> problems +=
                        "${attempt.path} was modified by something else during the run (left untouched; " +
                            "original in backup)"
                    confirmedAbsent == true -> problems +=
                        "${attempt.path} no longer exists (original preserved in backup; not recreated)"
                    else -> problems +=
                        "${attempt.path} could not be read during recovery (unknown state; original in backup)"
                }
            }
        }

        val state = if (problems.isEmpty()) "ROLLED_BACK" else "RECOVERY_REQUIRED"
        journal = journal.copy(state = state, error = cause.message)
        writeJournalAtomic(journal)

        val verifiedCleaned = journal.cleanedReferenceIds
        val possiblyCleaned = journal.possiblyCleanedReferenceIds
        val retainedIds = journal.referenceIds - verifiedCleaned.toSet() - possiblyCleaned.toSet()
        fun pathsOf(ids: List<String>) = ids.mapNotNull { sourcePathsById[it] }.distinct()

        buildString {
            append("Integration failed: ${cause.message}. ")
            if (problems.isEmpty()) {
                append("All ${restored.size} mutated file(s) were restored and verified by read-back ")
                append("(journal $state). ")
            } else {
                append("RECOVERY REQUIRED ($state) - safe automatic restore was not possible for: ")
                append(problems.joinToString("; ")).append(". ")
            }
            append("Originals of every planned file are preserved in ${backupDir.absolutePath}. ")
            append("Source status - verified cleaned: ")
            append(pathsOf(verifiedCleaned).ifEmpty { listOf("none") }.joinToString(", "))
            append("; possibly cleaned (state unknown, check the file): ")
            append(pathsOf(possiblyCleaned).ifEmpty { listOf("none") }.joinToString(", "))
            append("; retained / not attempted: ")
            append(pathsOf(retainedIds).ifEmpty { listOf("none") }.joinToString(", "))
            append(". Tell the user this partial state honestly and reference journal ${journal.id}.")
        }
    }

    /**
     * Positively confirms a document no longer exists. Deliberately does
     * NOT use DocumentFile.exists(): androidx.documentfile 1.0.1's
     * DocumentsContractApi19.exists CATCHES every query/provider/permission
     * exception and returns false, which would turn an unknown state into
     * fake "absent". Instead the provider is queried directly:
     * - non-null cursor with zero rows  -> true (confirmed absent);
     * - a row                            -> false (exists);
     * - null cursor or ANY exception     -> null (unknown, conservative).
     * FileNotFoundException and friends stay unknown: absence is never
     * inferred without an explicit successful empty query. The cursor is
     * always closed.
     */
    private fun confirmedAbsent(uri: Uri): Boolean? = try {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null
        )
        if (cursor == null) null else cursor.use { it.count == 0 }
    } catch (_: Exception) {
        null
    }

    /** Marks the newest attempt verified (checkpoint after a verified write). */
    private fun markVerified(
        journal: OrgIntegrationJournal,
        attempts: List<AttemptRecord>
    ): OrgIntegrationJournal {
        if (journal.writes.isEmpty()) return journal
        val verifiedCount = journal.writes.count { it.verified }
        val updated = journal.writes.mapIndexed { index, write ->
            // The (verifiedCount)-th intent corresponds to the newest
            // in-memory attempt that just verified.
            if (index == verifiedCount) write.copy(verified = true) else write
        }
        return journal.copy(writes = updated)
    }

    // ------------------------------------------------------------------
    // helpers: source state
    // ------------------------------------------------------------------

    private data class CreateTarget(
        val dest: OrgDestinationCreate,
        val parent: DocumentFile,
        val finalName: String
    )

    private data class UpdateTarget(
        val dest: OrgDestinationUpdate,
        val file: DocumentFile,
        val currentContent: String,
        val currentSha256: String
    )

    private data class SourceState(
        val snapshot: OrgSourceSnapshot,
        /** Subtree text as captured (byte-exact match is the identity). */
        val subtreeText: String,
        /** Live file content at validation time (for backup/journal). */
        val currentContent: String
    )

    /**
     * Verifies a source against the live file. Fast path: the subtree is
     * byte-identical at its captured offset. Slow path: the file drifted
     * (offsets may have moved); relocate by searching the EXACT captured
     * subtree text on heading boundaries - never by title, and never a
     * quoted copy inside another node's body. Zero or multiple boundary
     * matches = stale/ambiguous rejection with the source retained.
     */
    private suspend fun resolveSourceState(snapshot: OrgSourceSnapshot): SourceState {
        val uri = Uri.parse(snapshot.uri)
        val current = try {
            readRaw(uri)
        } catch (e: Exception) {
            throw OrgIntegrationReject(
                "Source file ${snapshot.relativePath} can no longer be read: ${e.message}. " +
                    "Source retained; nothing was written."
            )
        }
        // Identity = the FULL captured subtree, byte-exact, on a closing
        // boundary. The offset fast path re-checks the boundary too: if the
        // source gained a new deeper child right after the captured text,
        // those bytes are a stale prefix of a bigger subtree, and deleting
        // them would orphan the new children. Relocation accepts a unique
        // complete-boundary match only. Never matched by title, and never a
        // quoted copy inside another node's body.
        val level = snapshot.headingLevel
        val boundaryOccurrences = subtreeBoundaryOccurrences(current, snapshot.subtreeText, level)
        val atOffset = current.startsWith(snapshot.subtreeText, snapshot.headingOffset) &&
            isAtHeadingBoundary(current, snapshot.headingOffset, snapshot.subtreeText.length, level)
        val relocated = !atOffset && boundaryOccurrences.size == 1
        if (!atOffset && !relocated) {
            throw OrgIntegrationReject(
                if (boundaryOccurrences.isEmpty()) {
                    "Source item \"${snapshot.title.take(50)}\" in ${snapshot.relativePath} changed since it " +
                        "was selected for /org; its full captured subtree (heading, body and all children) " +
                        "can no longer be found on a heading boundary. Source retained; nothing was written. " +
                        "Ask the user to re-select the reference."
                } else {
                    "Source item \"${snapshot.title.take(50)}\" in ${snapshot.relativePath} is ambiguous: its " +
                        "exact subtree appears on ${boundaryOccurrences.size} heading boundaries. Source " +
                        "retained; nothing was written."
                }
            )
        }
        return SourceState(snapshot, snapshot.subtreeText, current)
    }

    // ------------------------------------------------------------------
    // helpers: journal
    // ------------------------------------------------------------------

    private fun sourceIdentitiesOf(selected: List<OrgSourceSnapshot>): List<String> =
        selected.map { "${it.relativePath}$IDENTITY_SEP${sha256(it.subtreeText)}" }.sorted()

    /**
     * Replay blocking, decided by OVERLAP of stable source identities (path
     * + subtree hash), not set equality:
     * - COMPLETED + overlap  -> already integrated and cleaned; refuse.
     * - MUTATING / DESTINATIONS_WRITTEN / FAILED / RECOVERY_REQUIRED + overlap
     *   -> an earlier attempt over these sources died unresolved; refuse to
     *   replay and point at the journal.
     * - PLANNED / ROLLED_BACK -> nothing was mutated, or every mutation was
     *   verified restored; a fresh attempt is safe.
     * A same-session directory whose journal cannot be parsed is treated as
     * unresolved (conservative): refuse rather than guess.
     */
    private fun checkReplayBlocking(sessionId: String, selected: List<OrgSourceSnapshot>) {
        val requested = sourceIdentitiesOf(selected).toSet()
        val sessionTag = "-${sessionId.take(8)}"
        val root = File(context.filesDir, JOURNAL_DIR)
        val dirs = root.listFiles { f -> f.isDirectory } ?: return
        for (dir in dirs.sortedByDescending { it.name }) {
            val journalFile = File(dir, "journal.json")
            if (!journalFile.exists()) continue
            val journal = readJournal(dir)
            if (journal == null) {
                if (dir.name.endsWith(sessionTag)) {
                    throw OrgIntegrationReject(
                        "An earlier integration attempt in this session left an unreadable journal " +
                            "(${dir.absolutePath}). Refusing to replay blindly; inspect it and the backups " +
                            "there before retrying."
                    )
                }
                continue
            }
            if (journal.sessionId != sessionId) continue
            if (journal.sourceIdentities.none { it in requested }) continue // no overlap
            when (journal.state) {
                "COMPLETED" -> throw OrgIntegrationReject(
                    "Source item(s) of this request were already integrated and their originals cleaned in " +
                        "attempt ${journal.id}. Refusing to run twice - tell the user the originals are " +
                        "already gone."
                )
                "MUTATING", "DESTINATIONS_WRITTEN" -> throw OrgIntegrationReject(
                    "An earlier integration attempt (${journal.id}) over overlapping source items died with " +
                        "mutations in flight; its effects are not fully resolved. Refusing to replay. " +
                        "Originals backup: ${journal.backupDir}. Tell the user to check that directory."
                )
                "FAILED", "RECOVERY_REQUIRED" -> throw OrgIntegrationReject(
                    "An earlier integration attempt (${journal.id}) over overlapping source items ended in " +
                        "${journal.state} and was not cleanly resolved. Refusing to replay. Originals " +
                        "backup: ${journal.backupDir}."
                )
                // PLANNED (nothing mutated) / ROLLED_BACK (verified restored):
                else -> Unit
            }
        }
    }

    /** Atomic-ish replacement: tmp file + rename, so a crash never tears the journal. */
    private fun writeJournalAtomic(journal: OrgIntegrationJournal) {
        val dir = journal.backupDir?.let(::File) ?: return
        val target = File(dir, JOURNAL_FILE_NAME)
        val tmp = File(dir, "$JOURNAL_FILE_NAME.tmp")
        val text = json.encodeToString(OrgIntegrationJournal.serializer(), journal)
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            // Degenerate fallback (rename refused): direct write; the tmp
            // file keeps a second copy for manual recovery.
            target.writeText(text)
            tmp.writeText(text)
        }
    }

    private fun readJournal(dir: File): OrgIntegrationJournal? = runCatching {
        jsonLenient.decodeFromString(
            OrgIntegrationJournal.serializer(),
            File(dir, JOURNAL_FILE_NAME).readText()
        )
    }.getOrNull()

    // ------------------------------------------------------------------
    // helpers: text / paths / SAF
    // ------------------------------------------------------------------

    private fun requireMeaningful(content: String, path: String) {
        if (content.isBlank() || content.trim().length < MIN_DESTINATION_CHARS) {
            throw OrgIntegrationReject("Destination content for $path is empty or too short to be meaningful.")
        }
    }

    /**
     * Heading start offset -> (start, end) where end is the next heading of
     * level <= this one (or EOF). Raw text, never re-formatted.
     */
    private fun subtreeRange(content: String, headingOffset: Int, level: Int): Pair<Int, Int> {
        val next = HEADLINE_REGEX.findAll(content, startIndex = headingOffset + 1)
            .firstOrNull { it.groupValues[1].length <= level }
        val end = next?.range?.first ?: content.length
        return headingOffset to end
    }

    /**
     * Offsets where [subtree] occurs on a REAL, COMPLETE heading boundary:
     * the match must start at the beginning of a line, and the next heading
     * after it must CLOSE the subtree (level <= [rootLevel]) or the match
     * must run to EOF. A deeper heading right after the match means the
     * source grew new children since capture - the match is a stale prefix
     * of a bigger subtree, not the full current subtree, and deleting it
     * would orphan those children. Quoted/inline copies inside another
     * node's body fail the line-start or heading checks and never match.
     */
    private fun subtreeBoundaryOccurrences(content: String, subtree: String, rootLevel: Int): List<Int> {
        if (subtree.isEmpty()) return emptyList()
        val result = mutableListOf<Int>()
        var index = content.indexOf(subtree)
        while (index >= 0) {
            if (isAtHeadingBoundary(content, index, subtree.length, rootLevel)) result.add(index)
            index = content.indexOf(subtree, index + subtree.length)
        }
        return result
    }

    private fun isAtHeadingBoundary(content: String, start: Int, length: Int, rootLevel: Int): Boolean {
        if (start > 0 && content[start - 1] != '\n') return false // must begin a line
        val end = start + length
        if (end == content.length) return true // ran to EOF
        if (end == 0 || content[end - 1] != '\n') return false // must end at a line start
        val lineEnd = content.indexOf('\n', end).let { if (it == -1) content.length else it }
        val nextLine = content.substring(end, lineEnd)
        val stars = LEADING_STARS_REGEX.find(nextLine)?.value
        // The next real heading must close the captured subtree: a deeper
        // heading means the subtree changed (new children) - not a match.
        return stars != null && stars.length <= rootLevel
    }

    /** The subtree's range ONLY when it occurs complete on exactly one heading boundary; null otherwise. */
    private fun boundaryRange(content: String, subtree: String, rootLevel: Int): Pair<Int, Int>? {
        val occurrences = subtreeBoundaryOccurrences(content, subtree, rootLevel)
        if (occurrences.size != 1) return null
        val start = occurrences.first()
        return start to start + subtree.length
    }

    /** Removes [ranges] (start,end) from content, highest offset first. */
    private fun removeRanges(content: String, ranges: List<Pair<Int, Int>>): String {
        val sorted = ranges.sortedByDescending { it.first }
        var updated = content
        for ((start, end) in sorted) {
            updated = updated.substring(0, start) + updated.substring(end)
        }
        return updated
    }

    /** Canonical path relative to the tree root, resolved through the live tree. */
    private suspend fun canonicalRelativePath(uri: Uri): String {
        val root = documentTreeStore.requireTreeDocumentFile()
        val segments = mutableListOf<String>()
        if (!walkTo(root, uri, segments, depth = 0)) {
            throw OrgIntegrationReject("无法在笔记树中定位引用文件（${uri.lastPathSegment ?: uri}）")
        }
        if (segments.isEmpty()) throw OrgIntegrationReject("引用指向了笔记树根目录")
        return segments.joinToString("/")
    }

    /** Depth-first search recording path segments on the way out. */
    private fun walkTo(dir: DocumentFile, target: Uri, segments: MutableList<String>, depth: Int): Boolean {
        if (depth > MAX_TREE_DEPTH) return false
        for (child in dir.listFiles()) {
            if (child.uri == target) {
                child.name?.let { segments.add(it) }
                return true
            }
            if (child.isDirectory && walkTo(child, target, segments, depth + 1)) {
                child.name?.let { segments.add(it) }
                return true
            }
        }
        return false
    }

    /** Repository write path: SAF write + read-back verification + index sync. */
    private suspend fun writeVerified(file: DocumentFile, fileName: String, content: String) {
        orgFileRepository.writeOrgFile(
            OrgDocument(
                uri = file.uri,
                fileName = fileName,
                content = content,
                lastModified = System.currentTimeMillis(),
                nodes = emptyList(),
                preamble = ""
            )
        ).getOrThrow()
    }

    private suspend fun uriToDocumentFile(uri: Uri): DocumentFile {
        val file = DocumentFile.fromSingleUri(context, uri)
        require(file != null && file.exists()) { "Document no longer exists: $uri" }
        return file
    }

    /** Raw SAF read (no org-java parse): integration must tolerate any content. */
    private suspend fun readRaw(uri: Uri): String {
        val file = DocumentFile.fromSingleUri(context, uri)
        require(file != null && file.exists()) { "Document no longer exists: $uri" }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } ?: throw IllegalStateException("Could not open $uri for reading")
    }

    private fun writeBackup(dir: File, relativePath: String, content: String) {
        File(dir, relativePath.replace('/', '_')).writeText(content)
    }

    private fun buildSummary(created: List<String>, updated: List<String>, outcomes: List<OrgSourceOutcome>): String {
        val cleaned = outcomes.filter { it.cleaned }
        val retained = outcomes.filterNot { it.cleaned }
        return buildString {
            append("Integrated ")
            if (created.isNotEmpty()) append("created ").append(created.joinToString(", ")).append("; ")
            if (updated.isNotEmpty()) append("updated ").append(updated.joinToString(", ")).append("; ")
            append("cleaned ${cleaned.size} original item(s)")
            if (retained.isNotEmpty()) {
                append("; retained ${retained.size}: ")
                append(retained.joinToString("; ") { "${it.relativePath} - ${it.reason ?: "unspecified"}" })
            }
            append('.')
        }
    }

    private fun OrgNode.findBySourceOffset(offset: Int): OrgNode? {
        if (sourceOffset == offset) return this
        return children.firstNotNullOfOrNull { it.findBySourceOffset(offset) }
    }

    companion object {
        const val JOURNAL_DIR = "org-integration"
        const val MIN_DESTINATION_CHARS = 20
        const val MAX_TREE_DEPTH = 8

        const val PHASE_CREATE = "create"
        const val PHASE_UPDATE = "update"
        const val PHASE_CLEANUP = "cleanup"

        private const val JOURNAL_FILE_NAME = "journal.json"
        private const val IDENTITY_SEP = " :: "

        private val HEADLINE_REGEX = Regex("""(?m)^(\*+)\s+.*$""")
        private val LEADING_STARS_REGEX = Regex("""^\*+""")

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
