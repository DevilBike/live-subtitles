package dev.bike.livesubs.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlidingWindowBufferTest {

    private val frameSamples = AudioFrame.SAMPLE_RATE * AudioFrame.FRAME_MS / 1000

    /** Frame n is filled with the value n, so windows show which frames they contain. */
    private fun frame(n: Int) = AudioFrame(FloatArray(frameSamples) { n.toFloat() }, n * 100L)

    @Test
    fun emitsOneWindowPerHop() {
        val buffer = SlidingWindowBuffer(windowMs = 1_000, hopMs = 300)
        val emitted = (0 until 9).map { buffer.add(frame(it)) }
        assertEquals(listOf(2, 5, 8), emitted.indices.filter { emitted[it] != null })
    }

    @Test
    fun firstWindowIsShortAndStartsAtFirstFrame() {
        val buffer = SlidingWindowBuffer(windowMs = 1_000, hopMs = 200)
        assertNull(buffer.add(frame(0)))
        val window = buffer.add(frame(1))!!
        assertEquals(2 * frameSamples, window.samples.size)
        assertEquals(0L, window.startMs)
        assertEquals(200L, window.endMs)
    }

    @Test
    fun fullWindowHoldsOnlyTheNewestAudioInOrder() {
        val buffer = SlidingWindowBuffer(windowMs = 500, hopMs = 500)
        var last: AudioWindow? = null
        for (n in 0 until 13) buffer.add(frame(n))?.let { last = it }
        // 13 frames, a window every 5: the last one covers frames 5..9.
        val window = last!!
        assertEquals(5 * frameSamples, window.samples.size)
        assertEquals(500L, window.startMs)
        val expected = FloatArray(5 * frameSamples) { (5 + it / frameSamples).toFloat() }
        assertArrayEquals(expected, window.samples, 0f)
    }

    @Test
    fun windowsAreCopiesNotViewsOfTheRing() {
        val buffer = SlidingWindowBuffer(windowMs = 200, hopMs = 100)
        val first = buffer.add(frame(1))!!
        buffer.add(frame(2))
        assertEquals(1f, first.samples[0], 0f)
    }

    @Test
    fun resetDropsBufferedAudio() {
        val buffer = SlidingWindowBuffer(windowMs = 1_000, hopMs = 100)
        buffer.add(frame(0))
        buffer.reset()
        val window = buffer.add(frame(1))!!
        assertEquals(frameSamples, window.samples.size)
        assertEquals(100L, window.startMs)
    }

    @Test
    fun levelOfSilenceAndFullScale() {
        assertEquals(AudioFrame.MIN_DB, AudioFrame(FloatArray(10), 0).levelDb(), 0f)
        assertEquals(0f, AudioFrame(FloatArray(10) { 1f }, 0).levelDb(), 0.01f)
    }
}
