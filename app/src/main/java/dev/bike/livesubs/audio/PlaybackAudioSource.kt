package dev.bike.livesubs.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Captures what other apps are playing (AudioPlaybackCapture, Android 10+) as
 * 16 kHz mono frames. Android resamples and downmixes for us, so no DSP here.
 *
 * Apps that opt out of capture (see README) simply contribute silence.
 */
class PlaybackAudioSource(private val projection: MediaProjection) : AudioSource {

    // RECORD_AUDIO is granted in MainActivity before the service can start.
    @SuppressLint("MissingPermission")
    override fun frames(): Flow<AudioFrame> = flow {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AudioFrame.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val frameSamples = AudioFrame.SAMPLE_RATE * AudioFrame.FRAME_MS / 1000
        val minBytes = AudioRecord.getMinBufferSize(
            AudioFrame.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // A few frames of slack, but no more: a deep buffer only adds delay.
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBytes, frameSamples * 2 * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }

        val pcm = ShortArray(frameSamples)
        try {
            record.startRecording()
            while (true) {
                currentCoroutineContext().ensureActive()
                var filled = 0
                while (filled < frameSamples) {
                    val n = record.read(pcm, filled, frameSamples - filled, AudioRecord.READ_BLOCKING)
                    check(n >= 0) { "AudioRecord.read failed: $n" }
                    filled += n
                }
                val samples = FloatArray(frameSamples) { pcm[it] / 32768f }
                // The last sample was captured roughly now, so date the frame backwards from here.
                emit(AudioFrame(samples, SystemClock.elapsedRealtime() - AudioFrame.FRAME_MS))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}
