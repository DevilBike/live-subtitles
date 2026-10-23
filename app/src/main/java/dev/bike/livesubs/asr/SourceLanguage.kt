package dev.bike.livesubs.asr

import android.content.Context

/**
 * What language the audio is in. For Japanese and Korean, Whisper writes the
 * speech down in that language and a separate translator turns whole sentences
 * into English, which reads far better than small Whisper models translating
 * by themselves. [ANY] keeps Whisper's built-in translation for everything else.
 */
enum class SourceLanguage(
    /** Language codes Whisper may detect; a single one is fixed from the start. Empty means any. */
    val candidates: List<String>,
) {
    JAPANESE_OR_KOREAN(listOf("ja", "ko")),
    JAPANESE(listOf("ja")),
    KOREAN(listOf("ko")),
    ANY(emptyList());

    /** Whether Whisper transcribes and a [dev.bike.livesubs.translate.Translator] translates. */
    val usesTranslator: Boolean get() = candidates.isNotEmpty()

    companion object {
        private const val PREFS = "settings"
        private const val KEY = "source_language"

        fun selected(context: Context): SourceLanguage {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == name } ?: JAPANESE_OR_KOREAN
        }

        fun select(context: Context, language: SourceLanguage) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.name).apply()
        }
    }
}
