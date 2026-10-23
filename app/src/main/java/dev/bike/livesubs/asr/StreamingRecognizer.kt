package dev.bike.livesubs.asr

import dev.bike.livesubs.audio.AudioWindow
import kotlinx.coroutines.flow.Flow

/**
 * Turns a live stream of audio into a live stream of English subtitle text.
 *
 * [WhisperRecognizer] implements this with whisper.cpp in "translate" mode, which
 * detects the language, transcribes and translates to English in one pass. Input is the
 * overlapping windows from [dev.bike.livesubs.audio.slidingWindows]; see README
 * for the sliding window approach that keeps latency around a second.
 */
interface StreamingRecognizer {
    fun subtitles(audio: Flow<AudioWindow>): Flow<SubtitleUpdate>
}
