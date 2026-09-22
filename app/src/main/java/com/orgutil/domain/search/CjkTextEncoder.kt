package com.orgutil.domain.search

/**
 * Index-side text encoder for the full-text search table.
 *
 * Background: the FTS4 table uses the built-in `unicode61` tokenizer, which
 * treats a whole run of CJK characters as a single token. That makes
 * substring queries impossible for Chinese text ("测试" can never match the
 * token "中文测试混合"). A trigram tokenizer would solve this but requires
 * FTS5 + SQLite 3.34+, which cannot be relied on at minSdk 26 (framework
 * SQLite is older on many devices).
 *
 * Instead, when content is written to the FTS table, every maximal CJK run
 * is replaced by space-separated tokens covering every position:
 * - each consecutive character bigram ("中文测试" -> "中文 文测 测试"), and
 * - each single character ("中 文 测 试"), so length-1 queries match exactly.
 *
 * A query side counterpart lives in [FtsQueryBuilder]. Both must be kept in
 * sync; they are pinned together by FtsSearchSemanticsTest.
 *
 * Latin text passes through unchanged, so existing token/prefix semantics
 * ("todo" matching "todolist") are preserved.
 */
object CjkTextEncoder {

    private fun isCjk(c: Char): Boolean = c in '一'..'龥'

    /**
     * Returns the text to store in the FTS `content` column. Non-CJK text is
     * returned as-is (fast path); CJK runs are expanded as described above.
     */
    fun encodeForIndex(content: String): String {
        if (content.indexOfFirst { isCjk(it) } < 0) return content

        return buildString {
            var i = 0
            while (i < content.length) {
                val c = content[i]
                if (!isCjk(c)) {
                    append(c)
                    i++
                    continue
                }
                var end = i
                while (end < content.length && isCjk(content[end])) end++
                // The run is rewritten surrounded by spaces so it can never
                // merge with adjacent latin/digit tokens.
                append(' ')
                val runEnd = end
                for (k in i until runEnd - 1) {
                    append(content[k]).append(content[k + 1]).append(' ')
                }
                for (k in i until runEnd) {
                    append(content[k]).append(' ')
                }
                i = end
            }
        }
    }
}
