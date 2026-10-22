package dev.bike.livesubs.translate

/**
 * Optional second stage. Not needed while Whisper translates directly to
 * English; kept so a faster transcribe-only model can be paired with a
 * separate on-device translation model later if that turns out better.
 */
interface Translator {
    suspend fun translate(text: String, sourceLanguage: String): String
}
