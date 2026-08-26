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
import com.chineseboot.android.core.analysis.OverlayPresentation
import com.chineseboot.android.core.model.AnalysisSnapshot
import com.chineseboot.android.core.vision.ChartRecognitionResult
import com.chineseboot.android.core.vision.PixelRect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            combine(repository.snapshot, repository.recognition) { snapshot, recognition -> snapshot to recognition }
                .collect { (snapshot, recognition) ->
                    overlayView?.let { render(it, snapshot, recognition) }
                }
        }
    }

    private fun render(view: View, snapshot: AnalysisSnapshot?, recognition: ChartRecognitionResult?) {
        val statusText = view.findViewById<android.widget.TextView>(R.id.overlay_status)
        val detailText = view.findViewById<android.widget.TextView>(R.id.overlay_details)

        if (snapshot == null) {
            statusText.text = "CHINESE-BOOT\n${getString(R.string.status_scanning)}"
            detailText.text = ""
            return
        }

        val statusWord = OverlayPresentation.statusWord(snapshot.state)
        statusText.text = "CHINESE-BOOT\nStatus: $statusWord"

        val signalText = snapshot.signal?.name ?: "WAIT"
        val trendText = OverlayPresentation.trendWord(snapshot.trend)
        val recognitionQuality = recognition?.let { "${(it.overallConfidence * 100).toInt()}% (${it.candles.size} candles)" }
            ?: "n/a"
        val timestampText = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(snapshot.timestampMillis))

        detailText.text = buildString {
            append("Asset: ${snapshot.asset ?: getString(R.string.value_asset_unknown)}\n")
            append("Timeframe: ${snapshot.timeframeSeconds?.let { "${it}s" } ?: getString(R.string.value_timeframe_unknown)}\n")
            append("Recognized price: ${snapshot.price?.let { "%.5f".format(it) } ?: "UNAVAILABLE"}\n")
            append("Candle direction: ${snapshot.direction?.name ?: "UNAVAILABLE"}\n")
            append("Signal: $signalText\n")
            append("Confidence: ${snapshot.confidencePercent ?: 0}%\n")
            append("Trend: $trendText\n")
            append("RSI: ${snapshot.rsi?.let { "%.1f".format(it) } ?: "N/A"}\n")
            append("Support: ${snapshot.support?.let { "%.5f".format(it) } ?: "N/A"}\n")
            append("Resistance: ${snapshot.resistance?.let { "%.5f".format(it) } ?: "N/A"}\n")
            append("Reason: ${snapshot.reason ?: "-"}\n")
            append("Candle count: ${snapshot.analysisWindow}\n")
            append("Recognition: $recognitionQuality\n")
            append("Calibration: ${snapshot.calibrationQuality?.let { "${(it * 100).toInt()}%" } ?: "UNAVAILABLE"}\n")
            append("Pipeline: ${snapshot.recognitionStatus}\n")
            append("Timestamp: $timestampText")
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
