package dev.bike.livesubs.asr

import dev.bike.livesubs.audio.AudioFrame
import kotlin.math.log10
import kotlin.math.sqrt

/** How much of a window is speech, from per-chunk VAD probabilities. */
data class SpeechActivity(val speechMs: Int, val trailingSilenceMs: Int) {
    companion object {
        /** Silero VAD (and the energy fallback) score audio in 512-sample chunks: 32 ms at 16 kHz. */
        const val CHUNK_SAMPLES = 512
        const val CHUNK_MS = CHUNK_SAMPLES * 1000 / AudioFrame.SAMPLE_RATE

        fun fromProbs(probs: FloatArray, threshold: Float = 0.5f): SpeechActivity {
            val speechChunks = probs.count { it >= threshold }
            val trailing = probs.size - 1 - probs.indexOfLast { it >= threshold }
            return SpeechActivity(speechChunks * CHUNK_MS, trailing * CHUNK_MS)
        }

        /**
         * Fallback when the VAD model is unavailable: any chunk louder than
         * [thresholdDb] counts as speech. Music counts too, so it is a weaker filter.
         */
        fun energyProbs(samples: FloatArray, thresholdDb: Float = -45f): FloatArray {
            val chunks = (samples.size + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES
            return FloatArray(chunks) { c ->
                val from = c * CHUNK_SAMPLES
                val to = minOf(samples.size, from + CHUNK_SAMPLES)
                var sum = 0.0
                for (i in from until to) sum += samples[i] * samples[i]
                val rms = sqrt(sum / (to - from))
                if (rms > 0 && 20 * log10(rms) > thresholdDb) 1f else 0f
            }
        }
    }
}
