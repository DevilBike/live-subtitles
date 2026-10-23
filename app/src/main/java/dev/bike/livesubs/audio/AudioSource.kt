package dev.bike.livesubs.audio

import kotlinx.coroutines.flow.Flow

/** A live source of 100 ms [AudioFrame]s. [PlaybackAudioSource] is the real one. */
interface AudioSource {
    fun frames(): Flow<AudioFrame>
}
