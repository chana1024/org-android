package com.orgutil.domain.search

/**
 * Matcher for FILE_LIST search mode (non-database path).
 *
 * Semantics (product decision A): FILE_LIST search matches file names and
 * relative paths ONLY - it never reads .org file bodies. Full-text search of
 * file content lives in the FTS path (FULL_TEXT mode).
 *
 * Matching is the classic file-list semantics: every whitespace-separated
 * query word must appear in the name-or-path string, case-insensitively
 * (substring containment, CJK included).
 */
object FileListQueryMatcher {

    /** Returns true when [nameOrPath] matches every word of [query]. */
    fun matches(nameOrPath: String, query: String): Boolean {
        val words = query.trim()
            .lowercase()
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val haystack = nameOrPath.lowercase()
        return words.all { haystack.contains(it) }
    }
}
