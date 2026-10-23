package dev.bike.livesubs.asr

import android.content.Context
import android.util.Log
import dev.bike.livesubs.translate.MlKitTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Multilingual Whisper models in whisper.cpp's quantized ggml format. All of
 * them can translate to English (unlike large-v3-turbo, which can't).
 */
enum class WhisperModel(val fileName: String, val approxMb: Int) {
    TINY("ggml-tiny-q8_0.bin", 44),
    BASE("ggml-base-q8_0.bin", 82),
    SMALL("ggml-small-q5_1.bin", 190);

    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

/**
 * Downloads the models on first use: the Whisper model and VAD model into the
 * app's private storage, and Google's Japanese and Korean translation models
 * through ML Kit. This is the only time the app uses the network: recognition
 * and translation then run entirely on the phone.
 */
object ModelStore {
    private const val TAG = "ModelStore"
    private const val VAD_FILE = "ggml-silero-v6.2.0.bin"
    private const val VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/$VAD_FILE"
    private const val PREFS = "models"
    private const val KEY_SELECTED = "selected"
    private const val KEY_TRANSLATION = "translation_ready"
    private val TRANSLATION_LANGUAGES = listOf("ja", "ko")
    private const val TRANSLATION_MB = 60

    sealed interface Download {
        data object Idle : Download
        /** [total] is 0 while the translation models download, which reports no progress. */
        data class Running(val model: WhisperModel, val bytes: Long, val total: Long) : Download
        data class Failed(val model: WhisperModel, val message: String) : Download
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _download = MutableStateFlow<Download>(Download.Idle)
    val download: StateFlow<Download> = _download.asStateFlow()

    fun selected(context: Context): WhisperModel {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SELECTED, null)
        return WhisperModel.entries.firstOrNull { it.name == name } ?: WhisperModel.BASE
    }

    fun select(context: Context, model: WhisperModel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SELECTED, model.name).apply()
    }

    private fun dir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }
    fun modelFile(context: Context, model: WhisperModel) = File(dir(context), model.fileName)
    fun vadFile(context: Context) = File(dir(context), VAD_FILE)

    private fun translationReady(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TRANSLATION, false)

    fun isReady(context: Context, model: WhisperModel, source: SourceLanguage) =
        modelFile(context, model).exists() && vadFile(context).exists() &&
            (!source.usesTranslator || translationReady(context))

    /** Roughly how much [startDownload] still has to fetch. */
    fun missingMb(context: Context, model: WhisperModel, source: SourceLanguage): Int =
        (if (modelFile(context, model).exists()) 0 else model.approxMb) +
            (if (vadFile(context).exists()) 0 else 1) +
            (if (source.usesTranslator && !translationReady(context)) TRANSLATION_MB else 0)

    /** Re-checks the translation models, in case Android removed them. */
    fun refreshTranslationState(context: Context) {
        val app = context.applicationContext
        if (!translationReady(app)) return
        scope.launch {
            val present = runCatching { MlKitTranslator.isDownloaded(TRANSLATION_LANGUAGES) }.getOrDefault(true)
            if (!present) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TRANSLATION, false).apply()
            }
        }
    }

    /** Removes a model file, e.g. after it failed to load. */
    fun delete(context: Context, model: WhisperModel) {
        modelFile(context, model).delete()
    }

    fun startDownload(context: Context, model: WhisperModel, source: SourceLanguage) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            try {
                _download.value = Download.Running(model, 0, model.approxMb * 1_000_000L)
                // The VAD model is under 1 MB; fetch it alongside the first speech model.
                if (!vadFile(app).exists()) fetch(VAD_URL, vadFile(app)) { _, _ -> }
                if (!modelFile(app, model).exists()) {
                    fetch(model.url, modelFile(app, model)) { bytes, total ->
                        _download.value = Download.Running(model, bytes, total)
                    }
                }
                if (source.usesTranslator && !translationReady(app)) {
                    _download.value = Download.Running(model, 0, 0)
                    MlKitTranslator.download(TRANSLATION_LANGUAGES)
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TRANSLATION, true).apply()
                }
                _download.value = Download.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Download of ${model.fileName} failed", e)
                _download.value = Download.Failed(model, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun fetch(url: String, target: File, progress: (Long, Long) -> Unit) {
        val part = File(target.path + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode} for ${target.name}")
            }
            val total = conn.contentLengthLong
            var bytes = 0L
            var reported = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        bytes += n
                        if (bytes - reported >= 512 * 1024) {
                            reported = bytes
                            progress(bytes, total)
                        }
                    }
                }
            }
            if (total > 0 && bytes != total) throw IOException("Download of ${target.name} was cut short")
            if (!part.renameTo(target)) throw IOException("Could not save ${target.name}")
        } finally {
            conn.disconnect()
            part.delete()
        }
    }
}
