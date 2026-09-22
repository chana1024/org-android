package com.orgutil.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkTextEncoderTest {

    @Test
    fun `latin content passes through unchanged`() {
        val content = "* TODO buy milk\nsome notes"
        assertEquals(content, CjkTextEncoder.encodeForIndex(content))
    }

    @Test
    fun `cjk run expands to consecutive bigrams and single chars`() {
        val encoded = CjkTextEncoder.encodeForIndex("中文测试")
        for (bigram in listOf("中文", "文测", "测试")) {
            assertTrue("missing bigram $bigram", encoded.contains("$bigram "))
        }
        for (char in listOf("中", "文", "测", "试")) {
            assertTrue("missing char $char", encoded.contains("$char "))
        }
    }

    @Test
    fun `cjk run is surrounded by spaces so it cannot merge with latin tokens`() {
        val encoded = CjkTextEncoder.encodeForIndex("todo中文")
        // "todo" and "中" must not form a single token like "todo中".
        assertFalse(encoded.contains("todo中"))
        assertTrue(encoded.contains("todo "))
        assertTrue(encoded.contains("中文 "))
    }

    @Test
    fun `mixed text keeps latin segments and expands cjk segments`() {
        val encoded = CjkTextEncoder.encodeForIndex("计划-2024 plan")
        assertTrue(encoded.contains("计划 "))
        assertTrue(encoded.contains("2024"))
        assertTrue(encoded.contains("plan"))
    }

    @Test
    fun `repeated characters still produce all position bigrams`() {
        val encoded = CjkTextEncoder.encodeForIndex("天天向上")
        assertTrue(encoded.contains("天天 "))
        assertTrue(encoded.contains("天向 "))
        assertTrue(encoded.contains("向上 "))
    }
}
