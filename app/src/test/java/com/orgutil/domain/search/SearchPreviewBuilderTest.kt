package com.orgutil.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SearchPreviewBuilderTest {

    @Test
    fun `build returns paragraph preview with query highlight and content offset`() {
        val content = """
            Intro line

            * Project
            This paragraph contains Needle and more detail.
            It continues on the next line.

            Other paragraph
        """.trimIndent()

        val preview = SearchPreviewBuilder.build(content, "needle")

        assertNotNull(preview)
        requireNotNull(preview)
        assertEquals("* Project\nThis paragraph contains Needle and more detail.\nIt continues on the next line.", preview.text)
        assertEquals(preview.text.indexOf("Needle"), preview.matchStart)
        assertEquals("Needle".length, preview.matchLength)
        assertEquals(content.indexOf("Needle"), preview.contentOffset)
    }

    @Test
    fun `build uses the first matching query term for multi word queries`() {
        val content = "alpha beta gamma"

        val preview = SearchPreviewBuilder.build(content, "missing beta")

        assertNotNull(preview)
        requireNotNull(preview)
        assertEquals("alpha beta gamma", preview.text)
        assertEquals("alpha ".length, preview.matchStart)
        assertEquals("beta".length, preview.matchLength)
        assertEquals(content.indexOf("beta"), preview.contentOffset)
    }
}
