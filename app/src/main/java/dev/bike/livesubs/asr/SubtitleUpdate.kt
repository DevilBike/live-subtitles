package dev.bike.livesubs.asr

/**
 * What the recognizer emits for the overlay to draw.
 *
 * [committed] text is stable and never changes again (only the most recent
 * part is kept; older text drops off the front). [tentative] text is the
 * recognizer's current best guess for the newest audio and may be rewritten by
 * the next update. Showing tentative text is what makes subtitles feel live.
 */
data class SubtitleUpdate(
    val committed: String,
    val tentative: String,
    /** Detected source language, e.g. "ja", or null until known. */
    val sourceLanguage: String?,
)
