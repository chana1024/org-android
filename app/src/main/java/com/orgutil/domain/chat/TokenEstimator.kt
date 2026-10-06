package com.orgutil.domain.chat

/**
 * Conservative character-based token estimate. No invented per-model
 * limits: callers configure a budget, this only estimates weight.
 * CJK chars count ~1 token each, other text ~3.5 chars/token, rounded up.
 */
object TokenEstimator {
    fun estimate(text: String): Long {
        if (text.isEmpty()) return 0L
        var cjk = 0L
        var other = 0L
        for (ch in text) {
            val block = Character.UnicodeBlock.of(ch)
            val isCjk = block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.HANGUL_SYLLABLES
            if (isCjk) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }
}
