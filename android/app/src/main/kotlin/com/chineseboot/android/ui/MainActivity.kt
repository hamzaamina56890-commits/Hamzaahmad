package com.chineseboot.android.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.chineseboot.android.ChineseBootApp
import com.chineseboot.android.R
import com.chineseboot.android.capture.ScreenCaptureService
import com.chineseboot.android.core.capture.CaptureEvent
import com.chineseboot.android.core.capture.CaptureState
import com.chineseboot.android.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Main screen: START SCAN / STOP SCAN plus the permission flow required
 * before capture can begin (screen-capture permission, then overlay
 * permission). Configuration changes (rotation) are handled via
 * `android:configChanges` in the manifest so this Activity is not recreated
 * mid-flow; the actual capture state lives in [ChineseBootApp.captureRepository]
 * and survives regardless.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository get() = (application as ChineseBootApp).captureRepository

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            repository.dispatch(CaptureEvent.ScreenPermissionGranted)
            proceedToOverlayPermission(result.resultCode, result.data!!)
        } else {
            repository.dispatch(CaptureEvent.ScreenPermissionDenied)
            updateUi(CaptureState.STOPPED)
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        if (Settings.canDrawOverlays(this)) {
            repository.dispatch(CaptureEvent.OverlayPermissionGranted)
        } else {
            repository.dispatch(CaptureEvent.OverlayPermissionDenied)
            updateUi(CaptureState.STOPPED)
        }
    }

    private var pendingProjectionResultCode: Int? = null
    private var pendingProjectionData: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startScanButton.setOnClickListener { onStartScanClicked() }
        binding.stopScanButton.setOnClickListener { onStopScanClicked() }

        observeCaptureState()
    }

    private fun observeCaptureState() {
        lifecycleScope.launch {
            repository.captureState.collect { state -> updateUi(state) }
        }
        lifecycleScope.launch {
            repository.errorMessage.collect { message ->
                if (message != null) {
                    binding.errorText.text = message
                    binding.errorText.visibility = android.view.View.VISIBLE
                } else {
                    binding.errorText.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun updateUi(state: CaptureState) {
        binding.statusText.text = getString(
            when (state) {
                CaptureState.IDLE -> R.string.status_idle
                CaptureState.AWAITING_SCREEN_PERMISSION -> R.string.status_awaiting_screen_permission
                CaptureState.AWAITING_OVERLAY_PERMISSION -> R.string.status_awaiting_overlay_permission
                CaptureState.CAPTURING -> R.string.status_capturing
                CaptureState.STOPPED -> R.string.status_idle
                CaptureState.ERROR -> R.string.status_error
            }
        )
        binding.startScanButton.isEnabled = state == CaptureState.IDLE || state == CaptureState.STOPPED || state == CaptureState.ERROR
        binding.stopScanButton.isEnabled = state == CaptureState.CAPTURING
    }

    private fun onStartScanClicked() {
        repository.dispatch(CaptureEvent.StartRequested)
        val projectionManager =
            getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun proceedToOverlayPermission(resultCode: Int, data: Intent) {
        pendingProjectionResultCode = resultCode
        pendingProjectionData = data

        if (Settings.canDrawOverlays(this)) {
            repository.dispatch(CaptureEvent.OverlayPermissionGranted)
            startCaptureService(resultCode, data)
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            overlaySettingsLauncher.launch(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the overlay-permission Settings screen.
        val resultCode = pendingProjectionResultCode
        val data = pendingProjectionData
        if (repository.captureState.value == CaptureState.AWAITING_OVERLAY_PERMISSION &&
            Settings.canDrawOverlays(this) && resultCode != null && data != null
        ) {
            repository.dispatch(CaptureEvent.OverlayPermissionGranted)
            startCaptureService(resultCode, data)
        }
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        ActivityCompat.startForegroundService(this, intent)
        pendingProjectionResultCode = null
        pendingProjectionData = null
    }

    private fun onStopScanClicked() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(intent)
        repository.dispatch(CaptureEvent.StopRequested)
    }
}
