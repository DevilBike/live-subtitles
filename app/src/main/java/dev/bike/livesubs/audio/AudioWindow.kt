package dev.bike.livesubs.audio

/**
 * The most recent few seconds of audio, handed to the recognizer as one chunk.
 *
 * Consecutive windows overlap heavily (e.g. 4 s windows every 0.5 s), so each
 * recognizer pass sees the newest words with enough context before them.
 */
class AudioWindow(
    /** 16 kHz mono float PCM; a private copy the consumer may keep. */
    val samples: FloatArray,
    /** Capture time of the first sample, in [android.os.SystemClock.elapsedRealtime] ms. */
    val startMs: Long,
) {
    val durationMs: Long get() = samples.size * 1000L / AudioFrame.SAMPLE_RATE
    val endMs: Long get() = startMs + durationMs
}
