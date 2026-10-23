package dev.bike.livesubs.asr

/**
 * The JNI surface of whisper.cpp (app/src/main/cpp/whisper_jni.cpp).
 * Handles are raw pointers; every call on one context must come from one thread.
 */
internal object WhisperNative {
    init {
        System.loadLibrary("livesubs_whisper")
    }

    external fun systemInfo(): String

    /** Loads a ggml model file. Returns 0 on failure. */
    external fun initContext(modelPath: String): Long
    external fun freeContext(ctx: Long)

    /** Loads a Silero VAD model file. Returns 0 on failure. */
    external fun initVad(modelPath: String, threads: Int): Long
    external fun freeVad(vad: Long)

    /** Speech probability per 32 ms chunk, or null on failure. */
    external fun vadProbs(vad: Long, samples: FloatArray): FloatArray?

    /** One pass over [samples]; [language] is a code like "ja" or "auto". Returns 0 on success. */
    external fun full(
        ctx: Long,
        samples: FloatArray,
        language: String,
        translate: Boolean,
        threads: Int,
        audioCtx: Int,
    ): Int

    /** Language of the last pass: the detected one if it ran with "auto". */
    external fun detectedLanguage(ctx: Long): String
    external fun segmentCount(ctx: Long): Int
    external fun segmentNoSpeechProb(ctx: Long, segment: Int): Float

    /** Text tokens of a segment: `t0, t1` per token in centiseconds, and their UTF-8 bytes. */
    external fun segmentTokenTimes(ctx: Long, segment: Int): LongArray
    external fun segmentTokenTexts(ctx: Long, segment: Int): Array<ByteArray>
}
