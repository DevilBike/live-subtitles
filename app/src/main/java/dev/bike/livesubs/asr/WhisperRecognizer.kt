package dev.bike.livesubs.asr

import android.os.SystemClock
import android.util.Log
import dev.bike.livesubs.audio.AudioFrame
import dev.bike.livesubs.audio.AudioWindow
import dev.bike.livesubs.audio.SlidingWindowBuffer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

sealed interface RecognizerStatus {
    data object Loading : RecognizerStatus
    data class Listening(val threads: Int, val vad: Boolean) : RecognizerStatus
}

/**
 * [StreamingRecognizer] on whisper.cpp, running Whisper's translate task so one
 * pass detects the language, transcribes and translates to English.
 *
 * Every window from the sliding buffer (the last 4 s, every 0.5 s) goes through:
 *  1. VAD (Silero): windows without speech are skipped, and a pause in speech
 *     makes the pass final, committing the sentence and starting the next
 *     window cleanly after the pause.
 *  2. whisper.cpp over the window, with the encoder shrunk to the window length
 *     and the last committed words as a prompt.
 *  3. [TranscriptStabilizer], which splits the words into committed and tentative.
 *
 * The language is auto-detected on the first speech, then pinned ([LanguageLock]).
 * Per-pass latency is logged under the `LiveSubsLatency` tag and published in [latency].
 */
class WhisperRecognizer(
    private val modelFile: File,
    private val vadFile: File?,
    /** CPU features the native library was built for, checked before it is loaded. */
    private val armArch: String,
    private val windowMs: Int = SlidingWindowBuffer.DEFAULT_WINDOW_MS,
    private val hopMs: Int = SlidingWindowBuffer.DEFAULT_HOP_MS,
) : StreamingRecognizer {

    private val _status = MutableStateFlow<RecognizerStatus>(RecognizerStatus.Loading)
    val status: StateFlow<RecognizerStatus> = _status.asStateFlow()

    private val _latency = MutableStateFlow(LatencyStats().snapshot())
    val latency: StateFlow<LatencyStats.Snapshot> = _latency.asStateFlow()

    override fun subtitles(audio: Flow<AudioWindow>): Flow<SubtitleUpdate> = flow {
        // whisper.cpp contexts are single-threaded; all native calls go through this one thread.
        val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "whisper") }.asCoroutineDispatcher()
        try {
            val session = withContext(dispatcher) { open() }
            try {
                audio.collect { window ->
                    withContext(dispatcher) { session.process(window) }?.let { emit(it) }
                }
            } finally {
                withContext(NonCancellable + dispatcher) { session.close() }
            }
        } finally {
            dispatcher.close()
        }
    }

    private fun open(): Session {
        val missing = CpuInfo.missingFeatures(armArch)
        check(missing.isEmpty()) {
            "This phone's CPU lacks ${missing.joinToString()}, which this build needs. " +
                "Rebuild with -Pwhisper.armArch=armv8-a (slower, see README)."
        }
        val threads = CpuInfo.recognizerThreads()
        Log.i(TAG, "Loading ${modelFile.name} with $threads threads. ${WhisperNative.systemInfo()}")
        val loadStart = SystemClock.elapsedRealtime()
        val ctx = WhisperNative.initContext(modelFile.path)
        if (ctx == 0L) {
            // Most likely a damaged download; delete it so the app offers to download it again.
            modelFile.delete()
            error("Could not load ${modelFile.name}. It was deleted, please download it again.")
        }
        val vad = vadFile?.takeIf { it.exists() }?.let { WhisperNative.initVad(it.path, 1) } ?: 0L
        if (vad == 0L) Log.w(TAG, "No VAD model, falling back to a loudness check")
        Log.i(TAG, "Model loaded in ${SystemClock.elapsedRealtime() - loadStart} ms")
        _status.value = RecognizerStatus.Listening(threads, vad != 0L)
        return Session(ctx, vad, threads)
    }

    private inner class Session(private val ctx: Long, private val vad: Long, private val threads: Int) {
        private val stabilizer = TranscriptStabilizer()
        private val language = LanguageLock()
        private val stats = LatencyStats()

        /** After a pause in speech, audio before this time is ignored so the next sentence starts clean. */
        private var cropFromMs = Long.MIN_VALUE

        fun process(window: AudioWindow): SubtitleUpdate? {
            val skip = ((cropFromMs - window.startMs) * AudioFrame.SAMPLE_RATE / 1000)
                .coerceIn(0, window.samples.size.toLong()).toInt()
            val samples = if (skip == 0) window.samples else window.samples.copyOfRange(skip, window.samples.size)
            val audioStartMs = window.startMs + skip * 1000L / AudioFrame.SAMPLE_RATE
            if (samples.size < MIN_SAMPLES) return flush()

            val probs = (if (vad != 0L) WhisperNative.vadProbs(vad, samples) else null)
                ?: SpeechActivity.energyProbs(samples)
            val activity = SpeechActivity.fromProbs(probs)
            if (activity.speechMs < MIN_SPEECH_MS) return flush()
            val final = activity.trailingSilenceMs >= END_OF_SPEECH_MS

            val lang = language.nextPassLanguage
            val prompt = stabilizer.prompt().takeIf { it.isNotEmpty() }?.toByteArray()
            val passStart = SystemClock.elapsedRealtime()
            val rc = WhisperNative.full(ctx, samples, lang, true, threads, audioCtxFor(samples.size), prompt)
            val passMs = SystemClock.elapsedRealtime() - passStart
            if (rc != 0) {
                Log.w(TAG, "whisper_full failed with $rc")
                return null
            }

            val words = ArrayList<Word>()
            for (s in 0 until WhisperNative.segmentCount(ctx)) {
                // Whisper invents text for music and noise; it flags such segments itself.
                if (WhisperNative.segmentNoSpeechProb(ctx, s) > NO_SPEECH_THRESHOLD) continue
                words += wordsFromTokens(
                    WhisperNative.segmentTokenTexts(ctx, s).asList(),
                    WhisperNative.segmentTokenTimes(ctx, s),
                    audioStartMs,
                )
            }
            if (lang == LanguageLock.AUTO && words.isNotEmpty()) {
                language.observe(WhisperNative.detectedLanguage(ctx), activity.speechMs)
            }

            // A full-length window loses its oldest hop of audio next time.
            val sliding = skip == 0 && window.durationMs >= windowMs - AudioFrame.FRAME_MS
            val cutoff = if (sliding) window.startMs + hopMs else Long.MIN_VALUE
            val result = stabilizer.update(words, cutoff, final)
            if (final) cropFromMs = window.endMs - activity.trailingSilenceMs + PAUSE_KEPT_MS

            val now = SystemClock.elapsedRealtime()
            val lagMs = now - window.endMs
            stats.recordPass(passMs, lagMs)
            result.newlyCommitted.forEach { stats.recordSettle(now - it.endMs) }
            _latency.value = stats.snapshot()
            Log.i(
                LATENCY_TAG,
                "pass=${passMs}ms lag=${lagMs}ms audio=${samples.size * 1000L / AudioFrame.SAMPLE_RATE}ms " +
                    "speech=${activity.speechMs}ms lang=$lang->${language.locked ?: language.latest} " +
                    "committed+${result.newlyCommitted.size} tentative=${result.tentative.size}" +
                    (if (final) " end-of-speech" else ""),
            )
            return SubtitleUpdate(
                committed = stabilizer.committedText,
                tentative = result.tentative.joinToString(" ") { it.text },
                sourceLanguage = language.locked ?: language.latest,
            )
        }

        /** No usable speech in this window: settle any text still waiting for agreement. */
        private fun flush(): SubtitleUpdate? {
            if (!stabilizer.hasTentative) return null
            stabilizer.flush()
            return SubtitleUpdate(stabilizer.committedText, "", language.locked ?: language.latest)
        }

        fun close() {
            WhisperNative.freeContext(ctx)
            WhisperNative.freeVad(vad)
        }
    }

    companion object {
        private const val TAG = "WhisperRecognizer"
        const val LATENCY_TAG = "LiveSubsLatency"

        private const val MIN_SAMPLES = AudioFrame.SAMPLE_RATE * 3 / 10
        private const val MIN_SPEECH_MS = 160
        /** This much silence at the end of a window counts as a pause between sentences. */
        private const val END_OF_SPEECH_MS = 700
        /** Silence kept before the next sentence, so its first word isn't clipped. */
        private const val PAUSE_KEPT_MS = 300
        private const val NO_SPEECH_THRESHOLD = 0.6f

        /**
         * Encoder context for [samples] of audio: 50 positions per second (1500
         * for Whisper's fixed 30 s), plus a little slack, rounded up to 32.
         */
        internal fun audioCtxFor(samples: Int): Int {
            val positions = (samples.toLong() * 50 + AudioFrame.SAMPLE_RATE - 1) / AudioFrame.SAMPLE_RATE
            return (((positions + 16 + 31) / 32) * 32).toInt().coerceAtMost(1500)
        }
    }
}
