package com.chezgoulet.phonon.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [InferenceServer.splitDeltas] — the streaming delta splitter.
 * Guards the CJK regression (#276): text without whitespace word boundaries
 * must still stream in multiple chunks instead of one giant event.
 */
class SplitDeltasTest {

    @Test
    fun `whitespace-separated text splits on word boundaries`() {
        val deltas = InferenceServer.splitDeltas("hello there world")
        assertEquals(listOf("hello ", "there ", "world"), deltas)
        // Reassembling the deltas reproduces the original text exactly.
        assertEquals("hello there world", deltas.joinToString(""))
    }

    @Test
    fun `CJK text without spaces falls back to fixed-size chunks`() {
        // 12 Han characters, no whitespace: the word regex yields a single
        // chunk, so the fallback must kick in and produce multiple deltas.
        val text = "你好世界今天天气很好啊哈哈"
        val deltas = InferenceServer.splitDeltas(text)
        assertTrue("expected multiple deltas for CJK text, got ${deltas.size}", deltas.size > 1)
        deltas.dropLast(1).forEach {
            assertEquals(InferenceServer.DELTA_FALLBACK_CHUNK, it.length)
        }
        // No characters lost or reordered.
        assertEquals(text, deltas.joinToString(""))
    }

    @Test
    fun `short text at or below fallback size stays a single delta`() {
        // Length <= DELTA_FALLBACK_CHUNK: not worth chunking further.
        val deltas = InferenceServer.splitDeltas("你好")
        assertEquals(listOf("你好"), deltas)
    }

    @Test
    fun `empty text yields no deltas`() {
        assertEquals(emptyList<String>(), InferenceServer.splitDeltas(""))
    }
}
