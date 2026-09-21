package com.orgutil.domain.search

data class SearchPreview(
    val text: String,
    val matchStart: Int,
    val matchLength: Int,
    val contentOffset: Int
)

object SearchPreviewBuilder {
    fun build(content: String, query: String): SearchPreview? {
        val terms = query.trim()
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
        if (content.isEmpty() || terms.isEmpty()) return null

        val match = terms
            .mapNotNull { term ->
                val index = content.indexOf(term, ignoreCase = true)
                if (index >= 0) Match(index, term.length) else null
            }
            .minByOrNull { it.offset }
            ?: return null

        val paragraphStart = content.lastIndexOf("\n\n", startIndex = match.offset)
            .let { if (it >= 0) it + 2 else 0 }
        val paragraphEnd = content.indexOf("\n\n", startIndex = match.offset)
            .let { if (it >= 0) it else content.length }

        val rawParagraph = content.substring(paragraphStart, paragraphEnd)
        val leadingTrim = rawParagraph.indexOfFirst { !it.isWhitespace() }
            .let { if (it >= 0) it else 0 }
        val trailingTrim = rawParagraph.indexOfLast { !it.isWhitespace() }
            .let { if (it >= 0) it + 1 else rawParagraph.length }
        val previewStartOffset = paragraphStart + leadingTrim
        val previewText = rawParagraph.substring(leadingTrim, trailingTrim)

        return SearchPreview(
            text = previewText,
            matchStart = match.offset - previewStartOffset,
            matchLength = match.length,
            contentOffset = match.offset
        )
    }

    private data class Match(
        val offset: Int,
        val length: Int
    )
}
