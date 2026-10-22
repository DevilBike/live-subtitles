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
import dev.bike.livesubs.databinding.ActivityMainBinding
import dev.bike.livesubs.service.CaptureService

/**
 * The only screen: walks the user through the three permissions the app needs,
 * then starts or stops [CaptureService].
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
        binding.status.setText(
            when {
                CaptureService.isRunning -> R.string.status_running
                audioOk && overlayOk -> R.string.status_ready
                else -> R.string.status_needs_permissions
            }
        )
    }
}
