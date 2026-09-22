package com.orgutil.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryBuilderTest {

    @Test
    fun `latin term becomes quoted prefix query`() {
        assertEquals("\"todo*\"", FtsQueryBuilder.buildMatchQuery("todo"))
    }

    @Test
    fun `multi word latin query ANDs quoted prefixes`() {
        assertEquals("\"todo*\" \"buy*\"", FtsQueryBuilder.buildMatchQuery("todo buy"))
    }

    @Test
    fun `cjk term becomes ANDed consecutive bigrams`() {
        assertEquals("\"文测\" \"测试\"", FtsQueryBuilder.buildMatchQuery("文测试"))
        assertEquals("\"中文\" \"文测\" \"测试\"", FtsQueryBuilder.buildMatchQuery("中文测试"))
    }

    @Test
    fun `single cjk char becomes exact match`() {
        assertEquals("\"测\"", FtsQueryBuilder.buildMatchQuery("测"))
    }

    @Test
    fun `mixed cjk and latin terms are combined`() {
        assertEquals("\"todo*\" \"中文\"", FtsQueryBuilder.buildMatchQuery("todo 中文"))
    }

    @Test
    fun `fts syntax characters are defused by quoting`() {
        // Must not be interpretable as column filters / operators: everything
        // ends up inside quoted strings.
        val query = FtsQueryBuilder.buildMatchQuery("path:todo NOT")
        assertEquals("\"path:todo*\" \"NOT*\"", query)
    }

    @Test
    fun `embedded quotes are doubled`() {
        assertEquals("\"a\"\"b*\"", FtsQueryBuilder.buildMatchQuery("a\"b"))
    }

    @Test
    fun `blank queries return null`() {
        assertNull(FtsQueryBuilder.buildMatchQuery(""))
        assertNull(FtsQueryBuilder.buildMatchQuery("   "))
    }

    @Test
    fun `punctuation-only queries return null`() {
        assertNull(FtsQueryBuilder.buildMatchQuery("***"))
        assertNull(FtsQueryBuilder.buildMatchQuery("... ---"))
    }
}
