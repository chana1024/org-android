package com.orgutil.domain.sync

/**
 * Result of one git sync run, mirroring the behavior of the user's
 * sync-git.sh script (auto commit, union pull, push-if-ahead).
 */
data class GitSyncOutcome(
    val committed: Boolean,
    val merged: Boolean,
    val pushed: Boolean,
    val conflictedFiles: List<String>
) {
    val summary: String
        get() = buildString {
            if (committed) append("Committed local changes; ")
            if (merged) append("Merged remote changes; ")
            append(if (pushed) "Pushed." else "Nothing to push.")
            if (conflictedFiles.isNotEmpty()) {
                append(" Union-merged conflicted files: ${conflictedFiles.joinToString(", ")}")
            }
        }
}

/** Lightweight repo state for display in the UI. */
data class GitRepoSnapshot(
    val repoRoot: String,
    val branch: String?,
    val ahead: Int,
    val behind: Int,
    val dirty: Boolean,
    val hasUpstream: Boolean
)
