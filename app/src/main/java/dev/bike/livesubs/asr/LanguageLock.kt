package dev.bike.livesubs.asr

/**
 * Detects the spoken language once, then pins it.
 *
 * Auto-detection costs an extra decoder run per pass and can flip on a short
 * or noisy window, so passes run with "auto" only until [confirmations] passes
 * in a row, each with at least [minSpeechMs] of speech, report the same language.
 */
class LanguageLock(
    /** A language known in advance, locked from the start. */
    fixed: String? = null,
    private val confirmations: Int = 2,
    private val minSpeechMs: Int = 1_500,
) {

    var locked: String? = fixed
        private set

    /** The most recent detection, shown while not yet locked. */
    var latest: String? = null
        private set

    private var streak = 0

    /** The language to request for the next pass. */
    val nextPassLanguage: String get() = locked ?: AUTO

    fun observe(language: String, speechMs: Int) {
        if (locked != null || language.isEmpty() || speechMs < minSpeechMs) return
        streak = if (language == latest) streak + 1 else 1
        latest = language
        if (streak >= confirmations) locked = language
    }

    companion object {
        const val AUTO = "auto"
    }
}
