package dev.bike.livesubs

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.bike.livesubs.audio.AudioFrame
import dev.bike.livesubs.databinding.ActivityMainBinding
import dev.bike.livesubs.service.CaptureService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The only screen: walks the user through the three permissions the app needs,
 * then starts or stops [CaptureService] and shows a live level meter of the
 * captured audio, so you can tell capture works before subtitles exist.
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
                CaptureService.start(this, result.resultCode, data)
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

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun hasRuntimePermissions() = runtimePermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshUi() {
        val audioOk = hasRuntimePermissions()
        val overlayOk = Settings.canDrawOverlays(this)

        binding.grantAudio.isEnabled = !audioOk
        binding.grantAudio.setText(if (audioOk) R.string.audio_granted else R.string.grant_audio)
        binding.grantOverlay.isEnabled = !overlayOk
        binding.grantOverlay.setText(if (overlayOk) R.string.overlay_granted else R.string.grant_overlay)

        binding.startStop.isEnabled = audioOk && overlayOk
        binding.startStop.setText(if (CaptureService.isRunning) R.string.stop else R.string.start)
        val error = CaptureService.captureError.value
        binding.status.text = when {
            CaptureService.isRunning -> getString(R.string.status_running)
            error != null -> getString(R.string.status_failed, error)
            audioOk && overlayOk -> getString(R.string.status_ready)
            else -> getString(R.string.status_needs_permissions)
        }
    }
}
