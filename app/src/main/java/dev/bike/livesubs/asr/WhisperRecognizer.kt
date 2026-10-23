package dev.bike.livesubs.asr

import android.os.SystemClock
import android.util.Log
import dev.bike.livesubs.audio.AudioFrame
import dev.bike.livesubs.audio.AudioWindow
import dev.bike.livesubs.audio.SlidingWindowBuffer
import dev.bike.livesubs.translate.Translator
import kotlinx.coroutines.CancellationException
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
 * [StreamingRecognizer] on whisper.cpp.
 *
 * Every window from the sliding buffer (the last 4 s, every 0.5 s) goes through:
 *  1. VAD (Silero): windows without speech are skipped, and a pause in speech
 *     makes the pass final, committing the sentence and starting the next
 *     window cleanly after the pause.
 *  2. whisper.cpp over the window, with the encoder shrunk to the window length.
 *     Phrases the model loops on ("so so so", a sentence twice) are collapsed.
 *  3. [TranscriptStabilizer], which splits the words into committed and tentative.
 *
 * With a [translator] (Japanese and Korean), Whisper writes the speech down in
 * its own language, [SentenceAssembler] gathers committed words into sentences,
 * and the translator turns each finished sentence into English; the unfinished
 * one is translated as tentative text. Without one, Whisper's translate task
 * goes straight to English for any language.
 *
 * The language is detected on the first speech, among [SourceLanguage.candidates]
 * if given, then pinned ([LanguageLock]). Per-pass latency is logged under the
 * `LiveSubsLatency` tag and published in [latency].
 */
class WhisperRecognizer(
    private val modelFile: File,
    private val vadFile: File?,
    /** CPU features the native library was built for, checked before it is loaded. */
    private val armArch: String,
    private val source: SourceLanguage = SourceLanguage.ANY,
    private val translator: Translator? = null,
    private val windowMs: Int = SlidingWindowBuffer.DEFAULT_WINDOW_MS,
    private val hopMs: Int = SlidingWindowBuffer.DEFAULT_HOP_MS,
) : StreamingRecognizer {

    init {
        require(source.usesTranslator == (translator != null)) { "$source needs a translator iff it transcribes" }
    }

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
                    val pass = withContext(dispatcher) { session.process(window) } ?: return@collect
                    // Translation runs here, off the whisper thread, so it never delays the next pass more than needed.
                    emit(session.render(pass))
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
        Log.i(TAG, "Loading ${modelFile.name} with $threads threads for $source. ${WhisperNative.systemInfo()}")
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

    /** What one window produced, before any translation. */
    private class Pass(
        val windowEndMs: Long,
        val passMs: Long,
        val newlyCommitted: List<Word>,
        val tentative: List<Word>,
        val final: Boolean,
        val log: String,
    )

    private inner class Session(private val ctx: Long, private val vad: Long, private val threads: Int) {
        private val stabilizer = TranscriptStabilizer()
        private val language = LanguageLock(fixed = source.candidates.singleOrNull())
        private val stats = LatencyStats()
        private val sentences = SentenceAssembler()
        private val english = StringBuilder()
        private var openSource = ""
        private var openEnglish = ""

        /** After a pause in speech, audio before this time is ignored so the next sentence starts clean. */
        private var cropFromMs = Long.MIN_VALUE

        fun process(window: AudioWindow): Pass? {
            val skip = ((cropFromMs - window.startMs) * AudioFrame.SAMPLE_RATE / 1000)
                .coerceIn(0, window.samples.size.toLong()).toInt()
            val samples = if (skip == 0) window.samples else window.samples.copyOfRange(skip, window.samples.size)
            val audioStartMs = window.startMs + skip * 1000L / AudioFrame.SAMPLE_RATE
            if (samples.size < MIN_SAMPLES) return flush(window)

            val probs = (if (vad != 0L) WhisperNative.vadProbs(vad, samples) else null)
                ?: SpeechActivity.energyProbs(samples)
            val activity = SpeechActivity.fromProbs(probs)
            if (activity.speechMs < MIN_SPEECH_MS) return flush(window)
            val final = activity.trailingSilenceMs >= END_OF_SPEECH_MS

            val passStart = SystemClock.elapsedRealtime()
            var lang = language.nextPassLanguage
            if (lang == LanguageLock.AUTO && source.candidates.isNotEmpty()) {
                // Choose only among the expected languages, then transcribe in that one.
                lang = WhisperNative.detectLanguage(ctx, samples, threads, source.candidates.toTypedArray())
                    ?: source.candidates.first()
                language.observe(lang, activity.speechMs)
            }
            val translate = !source.usesTranslator
            val rc = WhisperNative.full(ctx, samples, lang, translate, threads, audioCtxFor(samples.size))
            val passMs = SystemClock.elapsedRealtime() - passStart
            if (rc != 0) {
                Log.w(TAG, "whisper_full failed with $rc")
                return null
            }

            val recognized = ArrayList<Word>()
            for (s in 0 until WhisperNative.segmentCount(ctx)) {
                // Whisper invents text for music and noise; it flags such segments itself.
                if (WhisperNative.segmentNoSpeechProb(ctx, s) > NO_SPEECH_THRESHOLD) continue
                recognized += wordsFromTokens(
                    WhisperNative.segmentTokenTexts(ctx, s).asList(),
                    WhisperNative.segmentTokenTimes(ctx, s),
                    audioStartMs,
                )
            }
            // Japanese and Korean words are single tokens, so short legitimate repeats are common there.
            val words = collapseRepeats(recognized, minWords = if (translate) 2 else 4)
            if (lang == LanguageLock.AUTO && words.isNotEmpty()) {
                language.observe(WhisperNative.detectedLanguage(ctx), activity.speechMs)
            }

            // A full-length window loses its oldest hop of audio next time.
            val sliding = skip == 0 && window.durationMs >= windowMs - AudioFrame.FRAME_MS
            val cutoff = if (sliding) window.startMs + hopMs else Long.MIN_VALUE
            val result = stabilizer.update(words, cutoff, final)
            if (final) cropFromMs = window.endMs - activity.trailingSilenceMs + PAUSE_KEPT_MS

            val log = "pass=${passMs}ms audio=${samples.size * 1000L / AudioFrame.SAMPLE_RATE}ms " +
                "speech=${activity.speechMs}ms lang=$lang->${language.locked ?: language.latest} " +
                "committed+${result.newlyCommitted.size} tentative=${result.tentative.size}" +
                (if (recognized.size > words.size) " dropped-repeats=${recognized.size - words.size}" else "") +
                (if (final) " end-of-speech" else "")
            return Pass(window.endMs, passMs, result.newlyCommitted, result.tentative, final, log)
        }

        /** No usable speech in this window: settle any text still waiting for agreement. */
        private fun flush(window: AudioWindow): Pass? {
            if (!stabilizer.hasTentative && openSource.isEmpty()) return null
            val words = stabilizer.flush()
            return Pass(window.endMs, 0, words, emptyList(), final = true, log = "flush committed+${words.size}")
        }

        /** Turns a pass into subtitles, translating if needed, and records its latency. */
        suspend fun render(pass: Pass): SubtitleUpdate {
            val lang = language.locked ?: language.latest
            var translateMs = 0L
            val update = if (translator == null) {
                SubtitleUpdate(stabilizer.committedText, pass.tentative.joinText(), lang)
            } else {
                val start = SystemClock.elapsedRealtime()
                val r = sentences.update(pass.newlyCommitted, pass.tentative, pass.final)
                for (sentence in r.completed) appendEnglish(translate(sentence, lang))
                if (r.open != openSource) {
                    openSource = r.open
                    openEnglish = if (r.open.isBlank()) "" else translate(r.open, lang)
                }
                translateMs = SystemClock.elapsedRealtime() - start
                val sourceTail = listOf(stabilizer.committedText, pass.tentative.joinText())
                    .filter { it.isNotEmpty() }.joinToString(" ")
                SubtitleUpdate(english.toString(), openEnglish, lang, sourceTail.takeLast(SOURCE_CHARS))
            }

            val now = SystemClock.elapsedRealtime()
            val lagMs = now - pass.windowEndMs
            if (pass.passMs > 0) stats.recordPass(pass.passMs, lagMs)
            pass.newlyCommitted.forEach { stats.recordSettle(now - it.endMs) }
            _latency.value = stats.snapshot()
            Log.i(LATENCY_TAG, "${pass.log} translate=${translateMs}ms lag=${lagMs}ms")
            return update
        }

        private suspend fun translate(text: String, lang: String?): String {
            val code = lang ?: source.candidates.first()
            return try {
                translator!!.translate(text, code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Show the original rather than nothing; the log says why.
                Log.w(TAG, "Translation failed", e)
                text
            }
        }

        private fun appendEnglish(sentence: String) {
            if (sentence.isBlank()) return
            if (english.isNotEmpty()) english.append(' ')
            english.append(sentence.trim())
            if (english.length > ENGLISH_CHARS) {
                val cut = english.indexOf(" ", english.length - ENGLISH_CHARS)
                english.delete(0, if (cut > 0) cut + 1 else english.length - ENGLISH_CHARS)
            }
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
        private const val ENGLISH_CHARS = 1_000
        private const val SOURCE_CHARS = 200

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
