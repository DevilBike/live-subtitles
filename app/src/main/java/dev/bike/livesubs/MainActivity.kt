package dev.bike.livesubs

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.bike.livesubs.asr.ModelStore
import dev.bike.livesubs.asr.RecognizerStatus
import dev.bike.livesubs.asr.SourceLanguage
import dev.bike.livesubs.asr.WhisperModel
import dev.bike.livesubs.audio.AudioFrame
import dev.bike.livesubs.audio.SlidingWindowBuffer
import dev.bike.livesubs.databinding.ActivityMainBinding
import dev.bike.livesubs.service.CaptureService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The only screen: walks the user through the permissions and the one-time
 * model download, starts or stops [CaptureService], and shows the live
 * subtitles with their latency plus a level meter of the captured audio.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val runtimePermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }

    private val requestRuntimePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshUi() }

    private val requestOverlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshUi() }

    // The system "Start recording or casting?" dialog. Consent is single use, so we ask every time.
    private val requestScreenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                CaptureService.start(this, result.resultCode, data, ModelStore.selected(this), SourceLanguage.selected(this))
            }
            refreshUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.grantAudio.setOnClickListener { requestRuntimePermissions.launch(runtimePermissions) }
        binding.grantOverlay.setOnClickListener {
            requestOverlayPermission.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        binding.languageChoice.check(radioFor(SourceLanguage.selected(this)))
        binding.languageChoice.setOnCheckedChangeListener { _, id ->
            SourceLanguage.entries.firstOrNull { radioFor(it) == id }?.let { SourceLanguage.select(this, it) }
            refreshUi()
        }
        ModelStore.refreshTranslationState(this)

        binding.modelChoice.check(radioFor(ModelStore.selected(this)))
        binding.modelChoice.setOnCheckedChangeListener { _, id ->
            WhisperModel.entries.firstOrNull { radioFor(it) == id }?.let { ModelStore.select(this, it) }
            refreshUi()
        }
        binding.downloadModel.setOnClickListener {
            ModelStore.startDownload(this, ModelStore.selected(this), SourceLanguage.selected(this))
        }

        binding.startStop.setOnClickListener {
            if (CaptureService.isRunning) {
                CaptureService.stop(this)
                refreshUi()
            } else {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                requestScreenCapture.launch(mpm.createScreenCaptureIntent())
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CaptureService.running.collect { refreshUi() } }
                launch { CaptureService.captureError.collect { refreshUi() } }
                launch { ModelStore.download.collect { refreshUi() } }
                launch { CaptureService.subtitles.collect { showSubtitles() } }
                launch {
                    combine(
                        CaptureService.running,
                        CaptureService.recognizerStatus,
                        CaptureService.latency,
                        CaptureService.subtitles,
                    ) { _, _, _, _ -> }.collect { showRecognizerInfo() }
                }
                combine(
                    CaptureService.running,
                    CaptureService.levelDb,
                    CaptureService.windowCount,
                ) { running, db, windows -> Triple(running, db, windows) }
                    .collect { (running, db, windows) -> showLevel(running, db, windows) }
            }
        }
    }

    private fun showLevel(running: Boolean, db: Float, windows: Int) {
        // Map -60..0 dBFS onto the bar; quieter than that reads as empty.
        val percent = ((db - AudioFrame.SILENCE_DB) / -AudioFrame.SILENCE_DB * 100).toInt().coerceIn(0, 100)
        binding.level.setProgressCompat(if (running) percent else 0, false)
        binding.levelText.text = when {
            !running -> getString(R.string.level_off)
            db > AudioFrame.SILENCE_DB ->
                getString(R.string.level_hearing, db, CaptureService.windowSeconds.value, windows)
            else -> getString(R.string.level_silent)
        }
    }

    private fun showSubtitles() {
        val update = CaptureService.subtitles.value
        binding.sourceText.text = update?.sourceText.orEmpty()
        binding.sourceText.visibility = if (update?.sourceText.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (update == null) {
            binding.subtitles.text = getString(R.string.subtitles_off)
            return
        }
        // The last few lines only; tentative words in a lighter colour since they may still change.
        val committed = if (update.committed.length <= SUBTITLE_CHARS) update.committed
        else update.committed.takeLast(SUBTITLE_CHARS).substringAfter(' ')
        val text = SpannableStringBuilder(committed)
        if (update.tentative.isNotEmpty()) {
            if (text.isNotEmpty()) text.append(' ')
            val start = text.length
            text.append(update.tentative)
            val faded = ColorUtils.setAlphaComponent(binding.subtitles.currentTextColor, 0x80)
            text.setSpan(ForegroundColorSpan(faded), start, text.length, 0)
        }
        binding.subtitles.text = text
    }

    private fun showRecognizerInfo() {
        val status = CaptureService.recognizerStatus.value
        binding.recognizerInfo.text = if (!CaptureService.isRunning || status == null) {
            ""
        } else when (status) {
            RecognizerStatus.Loading -> getString(R.string.recognizer_loading)
            is RecognizerStatus.Listening -> {
                val lines = mutableListOf<String>()
                val language = CaptureService.subtitles.value?.sourceLanguage
                lines += if (language != null) getString(R.string.recognizer_language, language)
                else getString(R.string.recognizer_language_unknown)
                val stats = CaptureService.latency.value
                if (stats == null || stats.passes == 0) {
                    val vad = if (status.vad) "" else getString(R.string.recognizer_no_vad)
                    lines += getString(R.string.recognizer_waiting, status.threads, vad)
                } else {
                    val settle = stats.avgSettleMs?.let { getString(R.string.recognizer_settle, it / 1000f) }
                        ?: getString(R.string.recognizer_settle_none)
                    lines += getString(
                        R.string.recognizer_latency,
                        stats.lastPassMs.toInt(), stats.avgPassMs.toInt(), stats.avgLagMs / 1000f, settle,
                    )
                    if (stats.passes >= 5 && stats.avgPassMs > SlidingWindowBuffer.DEFAULT_HOP_MS * 2) {
                        lines += getString(R.string.recognizer_slow)
                    }
                }
                lines.joinToString("\n")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun hasRuntimePermissions() = runtimePermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun radioFor(language: SourceLanguage) = when (language) {
        SourceLanguage.JAPANESE_OR_KOREAN -> R.id.languageJaKo
        SourceLanguage.JAPANESE -> R.id.languageJa
        SourceLanguage.KOREAN -> R.id.languageKo
        SourceLanguage.ANY -> R.id.languageAny
    }

    private fun radioFor(model: WhisperModel) = when (model) {
        WhisperModel.TINY -> R.id.modelTiny
        WhisperModel.BASE -> R.id.modelBase
        WhisperModel.SMALL -> R.id.modelSmall
    }

    private fun WhisperModel.label() = name.lowercase().replaceFirstChar { it.uppercase() }

    private fun refreshModelUi(): Boolean {
        val model = ModelStore.selected(this)
        val source = SourceLanguage.selected(this)
        val ready = ModelStore.isReady(this, model, source)
        val download = ModelStore.download.value
        val downloading = download is ModelStore.Download.Running

        binding.modelChoice.isEnabled = !downloading
        for (m in WhisperModel.entries) findViewById<View>(radioFor(m)).isEnabled = !downloading
        for (l in SourceLanguage.entries) findViewById<View>(radioFor(l)).isEnabled = !downloading
        binding.downloadModel.isEnabled = !downloading && !ready
        binding.downloadProgress.visibility = if (downloading) View.VISIBLE else View.GONE
        when {
            download is ModelStore.Download.Running && download.total == 0L -> {
                binding.downloadModel.text = getString(R.string.model_downloading_translation)
                binding.downloadProgress.isIndeterminate = true
            }
            download is ModelStore.Download.Running -> {
                val percent = (download.bytes * 100 / download.total).toInt()
                binding.downloadModel.text = getString(R.string.model_downloading, download.model.label(), percent)
                binding.downloadProgress.isIndeterminate = false
                binding.downloadProgress.setProgressCompat(percent, true)
            }
            ready -> binding.downloadModel.text = getString(R.string.model_ready, model.label())
            else -> binding.downloadModel.text =
                getString(R.string.model_download, ModelStore.missingMb(this, model, source))
        }
        binding.modelStatus.text = when {
            download is ModelStore.Download.Failed && download.model == model ->
                getString(R.string.model_failed, download.message)
            CaptureService.isRunning -> getString(R.string.model_change_applies)
            else -> getString(R.string.model_hint)
        }
        return ready
    }

    private fun refreshUi() {
        val audioOk = hasRuntimePermissions()
        val overlayOk = Settings.canDrawOverlays(this)
        val modelOk = refreshModelUi()

        binding.grantAudio.isEnabled = !audioOk
        binding.grantAudio.setText(if (audioOk) R.string.audio_granted else R.string.grant_audio)
        binding.grantOverlay.isEnabled = !overlayOk
        binding.grantOverlay.setText(if (overlayOk) R.string.overlay_granted else R.string.grant_overlay)

        binding.startStop.isEnabled = CaptureService.isRunning || (audioOk && overlayOk && modelOk)
        binding.startStop.setText(if (CaptureService.isRunning) R.string.stop else R.string.start)
        val error = CaptureService.captureError.value
        binding.status.text = when {
            CaptureService.isRunning -> getString(R.string.status_running)
            error != null -> getString(R.string.status_failed, error)
            !(audioOk && overlayOk) -> getString(R.string.status_needs_permissions)
            !modelOk -> getString(R.string.status_needs_model)
            else -> getString(R.string.status_ready)
        }
    }

    private companion object {
        const val SUBTITLE_CHARS = 300
    }
}
