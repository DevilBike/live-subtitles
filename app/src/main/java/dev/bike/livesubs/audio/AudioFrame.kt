package dev.bike.livesubs.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A short slice of captured playback audio: 16 kHz mono float PCM in [-1, 1],
 * which is what Whisper and most on-device speech models expect.
 *
 * Frames are small (about 100 ms) so the recognizer can start work as soon as
 * speech begins instead of waiting for a long buffer to fill.
 */
class AudioFrame(
    val samples: FloatArray,
    /** Capture time of the first sample, in [android.os.SystemClock.elapsedRealtime] ms. */
    val startMs: Long,
) {
    val durationMs: Long get() = samples.size * 1000L / SAMPLE_RATE

    /** Loudness as RMS in dBFS: 0 is full scale, [SILENCE_DB] or lower is treated as silence. */
    fun levelDb(): Float {
        if (samples.isEmpty()) return MIN_DB
        var sum = 0.0
        for (s in samples) sum += s * s
        val rms = sqrt(sum / samples.size)
        return if (rms <= 0.0) MIN_DB else (20 * log10(rms)).toFloat().coerceAtLeast(MIN_DB)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 100
        const val MIN_DB = -90f
        const val SILENCE_DB = -60f
    }
}
