package dev.bike.livesubs.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Ring buffer over the last [windowMs] of audio that hands out an overlapping
 * [AudioWindow] every [hopMs].
 *
 * Windows start being emitted after the first hop, while still shorter than
 * [windowMs], so the first subtitles do not wait for the buffer to fill.
 */
class SlidingWindowBuffer(
    val windowMs: Int = DEFAULT_WINDOW_MS,
    val hopMs: Int = DEFAULT_HOP_MS,
) {
    init {
        require(hopMs in 1..windowMs) { "hopMs must be in 1..windowMs" }
    }

    private val capacity = AudioFrame.SAMPLE_RATE * windowMs / 1000
    private val hopSamples = AudioFrame.SAMPLE_RATE * hopMs / 1000
    private val ring = FloatArray(capacity)
    private var writePos = 0
    private var size = 0
    private var sinceLastWindow = 0
    private var endMs = 0L

    /** Adds a frame and returns a new window if a hop's worth of audio has arrived since the last one. */
    fun add(frame: AudioFrame): AudioWindow? {
        val src = frame.samples
        // Only the newest `capacity` samples can survive, so skip the rest of an oversized frame.
        val skip = (src.size - capacity).coerceAtLeast(0)
        var i = skip
        while (i < src.size) {
            val n = minOf(src.size - i, capacity - writePos)
            src.copyInto(ring, writePos, i, i + n)
            writePos = (writePos + n) % capacity
            i += n
        }
        size = minOf(capacity, size + src.size)
        endMs = frame.startMs + frame.durationMs
        sinceLastWindow += src.size

        if (sinceLastWindow < hopSamples) return null
        sinceLastWindow %= hopSamples
        return snapshot()
    }

    /** Drops buffered audio, e.g. after a long silence so the next sentence starts clean. */
    fun reset() {
        writePos = 0
        size = 0
        sinceLastWindow = 0
    }

    private fun snapshot(): AudioWindow {
        val out = FloatArray(size)
        val start = (writePos - size + capacity) % capacity
        val firstPart = minOf(size, capacity - start)
        ring.copyInto(out, 0, start, start + firstPart)
        ring.copyInto(out, firstPart, 0, size - firstPart)
        return AudioWindow(out, endMs - size * 1000L / AudioFrame.SAMPLE_RATE)
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 4_000
        const val DEFAULT_HOP_MS = 500
    }
}

/**
 * Turns 100 ms frames into overlapping windows for the recognizer.
 *
 * Add `.conflate()` after this when the consumer can be slower than [hopMs], so
 * it always gets the newest window instead of falling behind live audio.
 */
fun Flow<AudioFrame>.slidingWindows(
    windowMs: Int = SlidingWindowBuffer.DEFAULT_WINDOW_MS,
    hopMs: Int = SlidingWindowBuffer.DEFAULT_HOP_MS,
): Flow<AudioWindow> = flow {
    val buffer = SlidingWindowBuffer(windowMs, hopMs)
    collect { frame -> buffer.add(frame)?.let { emit(it) } }
}
