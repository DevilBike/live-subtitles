package dev.bike.livesubs.audio

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
    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 100
    }
}
