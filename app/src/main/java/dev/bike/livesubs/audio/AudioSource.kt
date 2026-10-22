package dev.bike.livesubs.audio

import kotlinx.coroutines.flow.Flow

/** Step 2 implements this with AudioPlaybackCapture on top of the service's MediaProjection. */
interface AudioSource {
    fun frames(): Flow<AudioFrame>
}
