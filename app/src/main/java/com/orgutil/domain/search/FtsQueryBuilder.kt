package com.orgutil.domain.search

/**
 * Builds FTS4 MATCH expressions for the full-text search.
 *
 * Must be used together with [CjkTextEncoder]-encoded index content:
 * - Latin terms become quoted prefix queries (`"todo*"`), preserving the
 *   previous token-prefix semantics.
 * - CJK terms become exact bigram matches (`"中文测试"` -> `"中文" "文测" "测试"`)
 *   or, for a single character, an exact character match (`"测"`). Together
 *   with the encoder this yields substring semantics: "测试" matches
 *   "中文测试混合".
 * - Every term is double-quoted, so user punctuation can never form FTS
 *   query syntax (`path:` filters, NOT/- operators, ...).
 *
 * Bigram AND-matching is position-approximate: a query run longer than two
 * characters requires all its bigrams to be present in the document, but not
 * necessarily adjacent. This is the standard bigram-index trade-off (same
 * class of approximation as FTS5 trigram) and is covered by
 * FtsSearchSemanticsTest.
 */
object FtsQueryBuilder {

    private fun isCjk(c: Char): Boolean = c in '一'..'龥'

    /**
     * Returns the MATCH expression for [rawQuery], or `null` when no term in
     * the query can ever match indexed content (e.g. whitespace- or
     * punctuation-only terms). Callers should treat `null` as "no results"
     * rather than issuing a query.
     */
    fun buildMatchQuery(rawQuery: String): String? {
        val terms = rawQuery.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (terms.isEmpty()) return null

        val expressions = terms.flatMap { term -> termExpressions(term) }
        return if (expressions.isEmpty()) null else expressions.joinToString(" ")
    }

    /** Splits a term into CJK runs and text segments, each mapped to its own expression. */
    private fun termExpressions(term: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < term.length) {
            if (isCjk(term[i])) {
                var end = i
                while (end < term.length && isCjk(term[end])) end++
                result += cjkRunExpressions(term.substring(i, end))
                i = end
            } else {
                var end = i
                while (end < term.length && !isCjk(term[end])) end++
                textSegmentExpression(term.substring(i, end))?.let { result += it }
                i = end
            }
        }
        return result
    }

    /**
     * A CJK run matches as the AND of its consecutive bigrams, or as a single
     * exact character when the run has length 1.
     */
    private fun cjkRunExpressions(run: String): List<String> =
        if (run.length == 1) {
            listOf(quote(run))
        } else {
            (0 until run.length - 1).map { k -> quote(run.substring(k, k + 2)) }
        }

    /**
     * Latin/other text matches as a prefix. FTS4 prefix syntax puts the '*'
     * INSIDE the quoted phrase (`"todo*"`); appending it after the closing
     * quote (`"todo"*`) is parsed as an exact phrase and matches nothing but
     * the exact token. Segments made purely of tokenizer separators (no
     * letters/digits/CJK) cannot match anything and are dropped instead of
     * producing an invalid empty FTS phrase.
     */
    private fun textSegmentExpression(segment: String): String? {
        if (segment.none { it.isLetterOrDigit() }) return null
        return "\"" + segment.replace("\"", "\"\"") + "*\""
    }

    private fun quote(term: String): String = "\"${term.replace("\"", "\"\"")}\""
}
