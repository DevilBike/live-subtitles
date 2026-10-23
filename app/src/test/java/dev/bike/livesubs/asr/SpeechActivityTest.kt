package dev.bike.livesubs.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechActivityTest {

    @Test
    fun countsSpeechAndTrailingSilence() {
        val probs = floatArrayOf(0.1f, 0.9f, 0.8f, 0.2f, 0.7f, 0.1f, 0.1f)
        assertEquals(SpeechActivity(3 * 32, 2 * 32), SpeechActivity.fromProbs(probs))
    }

    @Test
    fun allSilenceIsAllTrailing() {
        assertEquals(SpeechActivity(0, 3 * 32), SpeechActivity.fromProbs(FloatArray(3)))
    }

    @Test
    fun energyFallbackMarksLoudChunks() {
        val samples = FloatArray(512 * 3).also { s -> for (i in 512 until 1024) s[i] = 0.5f }
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), SpeechActivity.energyProbs(samples), 0f)
    }

    @Test
    fun energyFallbackHandlesAPartialLastChunk() {
        assertEquals(2, SpeechActivity.energyProbs(FloatArray(600) { 0.5f }).size)
    }
}
