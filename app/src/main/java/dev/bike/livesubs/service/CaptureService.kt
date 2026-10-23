package dev.bike.livesubs.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.bike.livesubs.BuildConfig
import dev.bike.livesubs.MainActivity
import dev.bike.livesubs.R
import dev.bike.livesubs.asr.LatencyStats
import dev.bike.livesubs.asr.ModelStore
import dev.bike.livesubs.asr.RecognizerStatus
import dev.bike.livesubs.asr.SubtitleUpdate
import dev.bike.livesubs.asr.WhisperModel
import dev.bike.livesubs.asr.WhisperRecognizer
import dev.bike.livesubs.audio.AudioFrame
import dev.bike.livesubs.audio.PlaybackAudioSource
import dev.bike.livesubs.audio.slidingWindows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the MediaProjection for as long as subtitles are on.
 *
 * It captures playback audio, publishes the live level for the app's meter,
 * runs the recognizer and publishes its subtitles for the app screen and the
 * notification:
 *   PlaybackAudioSource -> slidingWindows -> WhisperRecognizer -> SubtitleOverlay (step 4)
 */
class CaptureService : LifecycleService() {

    private var projection: MediaProjection? = null
    private var captureJob: Job? = null
    @Volatile private var hearingAudio = false
    private var lastLoudMs = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user revoked capture from the status bar, or another app took it over.
            Log.i(TAG, "MediaProjection stopped by the system")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+ requires startForeground() with the mediaProjection type
        // *before* getMediaProjection() is called.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_text)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "Started without screen capture consent")
            stopSelf()
            return START_NOT_STICKY
        }

        val model = WhisperModel.entries.firstOrNull { it.name == intent?.getStringExtra(EXTRA_MODEL) }
        if (model == null || !ModelStore.isReady(this, model)) {
            Log.w(TAG, "Started without a downloaded model")
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(resultCode, data)?.also {
            it.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            _running.value = true
            onProjectionReady(it, model)
        }
        if (projection == null) stopSelf()

        return START_NOT_STICKY
    }

    private fun onProjectionReady(projection: MediaProjection, model: WhisperModel) {
        Log.i(TAG, "MediaProjection ready, starting playback capture with ${model.fileName}")
        val recognizer = WhisperRecognizer(
            modelFile = ModelStore.modelFile(this, model),
            vadFile = ModelStore.vadFile(this),
            armArch = BuildConfig.WHISPER_ARM_ARCH,
        )
        captureJob = lifecycleScope.launch {
            launch { recognizer.status.collect { _recognizerStatus.value = it } }
            launch { recognizer.latency.collect { _latency.value = it } }
            launch { updateNotificationWithSubtitles() }
            try {
                val windows = PlaybackAudioSource(projection).frames()
                    .onEach { onFrame(it) }
                    .slidingWindows()
                    .onEach { window ->
                        _windowCount.value++
                        _windowSeconds.value = window.durationMs / 1000f
                    }
                    // Keep capture and buffering off the main thread and never blocked by the recognizer.
                    .flowOn(Dispatchers.Default)
                    // The recognizer may be slower than one hop; always hand it the newest window.
                    .conflate()
                recognizer.subtitles(windows).collect { update ->
                    // TODO(step 4): SubtitleOverlay.show() and render(update)
                    _subtitles.value = update
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } catch (e: LinkageError) {
                // The native library failed to load: report it rather than crash.
                fail(e)
            }
        }
    }

    private fun fail(e: Throwable) {
        Log.e(TAG, "Capture or recognition failed", e)
        _captureError.value = e.message ?: e.javaClass.simpleName
        stopSelf()
    }

    private fun onFrame(frame: AudioFrame) {
        val db = frame.levelDb()
        _levelDb.value = db
        val now = frame.startMs
        if (db > AudioFrame.SILENCE_DB) lastLoudMs = now
        // Hysteresis so the hearing state only changes on real transitions, not every quiet frame.
        hearingAudio = now - lastLoudMs < SILENCE_HOLD_MS
    }

    /**
     * Shows the newest subtitle text in the notification, or whether audio is
     * coming through while there is none. At most once a second, since Android
     * rate-limits notification updates.
     */
    private suspend fun updateNotificationWithSubtitles() {
        var shown: String? = null
        while (true) {
            val subtitle = _subtitles.value?.let { "${it.committed} ${it.tentative}".trim() }.orEmpty()
            val text = when {
                subtitle.isNotEmpty() -> subtitle.takeLastWords(NOTIFICATION_CHARS)
                hearingAudio -> getString(R.string.notification_hearing)
                else -> getString(R.string.notification_silent)
            }
            if (text != shown) {
                shown = text
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
            }
            delay(1_000)
        }
    }

    private fun String.takeLastWords(maxChars: Int): String {
        if (length <= maxChars) return this
        val cut = indexOf(' ', length - maxChars)
        return "…" + if (cut < 0) takeLast(maxChars) else substring(cut + 1)
    }

    override fun onDestroy() {
        // Stop reading before the projection goes away, so a normal stop is not reported as a failure.
        captureJob?.cancel()
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        _running.value = false
        _levelDb.value = AudioFrame.MIN_DB
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_subtitles)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.bike.livesubs.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_MODEL = "model"
        private const val NOTIFICATION_CHARS = 160

        private const val SILENCE_HOLD_MS = 2_000L

        private val _running = MutableStateFlow(false)
        private val _levelDb = MutableStateFlow(AudioFrame.MIN_DB)
        private val _windowCount = MutableStateFlow(0)
        private val _windowSeconds = MutableStateFlow(0f)
        private val _captureError = MutableStateFlow<String?>(null)
        private val _recognizerStatus = MutableStateFlow<RecognizerStatus?>(null)
        private val _latency = MutableStateFlow<LatencyStats.Snapshot?>(null)
        private val _subtitles = MutableStateFlow<SubtitleUpdate?>(null)

        val running: StateFlow<Boolean> = _running.asStateFlow()
        val isRunning: Boolean get() = _running.value

        /** Loudness of the latest 100 ms frame in dBFS, for the level meter. */
        val levelDb: StateFlow<Float> = _levelDb.asStateFlow()

        /** How many windows the buffer has produced, and the length of the latest one. */
        val windowCount: StateFlow<Int> = _windowCount.asStateFlow()
        val windowSeconds: StateFlow<Float> = _windowSeconds.asStateFlow()

        /** Why the last capture attempt failed, or null. */
        val captureError: StateFlow<String?> = _captureError.asStateFlow()

        /** The recognizer of the current run: loading or listening. Null before the first start. */
        val recognizerStatus: StateFlow<RecognizerStatus?> = _recognizerStatus.asStateFlow()

        /** Rolling latency figures of the current run. */
        val latency: StateFlow<LatencyStats.Snapshot?> = _latency.asStateFlow()

        /** The latest subtitles of the current run. */
        val subtitles: StateFlow<SubtitleUpdate?> = _subtitles.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent, model: WhisperModel) {
            _captureError.value = null
            _windowCount.value = 0
            _windowSeconds.value = 0f
            _recognizerStatus.value = null
            _latency.value = null
            _subtitles.value = null
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_MODEL, model.name)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}
