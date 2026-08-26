package com.chineseboot.android.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.chineseboot.android.ChineseBootApp
import com.chineseboot.android.R
import com.chineseboot.android.core.vision.ChartRecognitionResult
import com.chineseboot.android.core.vision.PixelRect
import com.chineseboot.android.core.vision.RecognitionState
import kotlinx.coroutines.launch

/**
 * Draws the small floating analysis panel using [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY].
 * Only starts once [Settings.canDrawOverlays] is true; the caller (MainActivity) is
 * responsible for driving the user through that permission first.
 */
class OverlayService : Service(), LifecycleOwner {

    companion object {
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            context.startService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    private val repository get() = (application as ChineseBootApp).captureRepository

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        addOverlayView()
        observeState()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlayView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.overlay_panel, null)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        makeDraggable(view, params)

        windowManager?.addView(view, params)
        overlayView = view

        view.post { publishOverlayBounds(view, params) }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> publishOverlayBounds(view, params) }
    }

    /** Reports the overlay's own on-screen bounds so the capture pipeline can exclude them. */
    private fun publishOverlayBounds(view: View, params: WindowManager.LayoutParams) {
        val width = view.width.takeIf { it > 0 } ?: return
        val height = view.height.takeIf { it > 0 } ?: return
        repository.updateOverlayBounds(PixelRect(params.x.coerceAtLeast(0), params.y.coerceAtLeast(0), width, height))
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(view, params)
                    publishOverlayBounds(view, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repository.recognition.collect { result ->
                overlayView?.let { render(it, result) }
            }
        }
    }

    private fun render(view: View, result: ChartRecognitionResult?) {
        val statusText = view.findViewById<android.widget.TextView>(R.id.overlay_status)
        val detailText = view.findViewById<android.widget.TextView>(R.id.overlay_details)

        if (result == null) {
            statusText.text = getString(R.string.status_scanning)
            detailText.text = ""
            return
        }

        statusText.text = when (result.state) {
            RecognitionState.CHART_NOT_DETECTED -> getString(R.string.status_chart_not_detected)
            RecognitionState.CANDLES_NOT_RELIABLE -> getString(R.string.status_candles_not_reliable)
            RecognitionState.CANDLE_COLOR_UNKNOWN -> getString(R.string.status_candle_color_unknown)
            RecognitionState.READY -> getString(R.string.status_ready)
        }

        detailText.text = buildString {
            append("Asset: ${result.asset ?: getString(R.string.value_asset_unknown)}\n")
            append("Timeframe: ${result.timeframeSeconds?.let { "${it}s" } ?: getString(R.string.value_timeframe_unknown)}\n")
            append("Candles detected: ${result.candles.size}\n")
            append("Candle quality: ${(result.candleQuality * 100).toInt()}%\n")
            append("Confidence: ${(result.overallConfidence * 100).toInt()}%\n")
            // Trade-signal generation is not implemented yet — never imply a decision here.
            append("Signal: WAIT")
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
