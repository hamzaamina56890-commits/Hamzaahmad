package com.chineseboot.android.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chineseboot.android.R
import com.chineseboot.android.ChineseBootApp
import com.chineseboot.android.analysis.ChartCaptureAnalyzer
import com.chineseboot.android.core.capture.CaptureEvent
import com.chineseboot.android.core.vision.FrameBuffer
import com.chineseboot.android.core.vision.PixelRect
import com.chineseboot.android.overlay.OverlayService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type `mediaProjection`) that owns the [MediaProjection]
 * session and samples frames from the visible screen.
 *
 * Lifecycle handling:
 *  - `onStartCommand` requires the permission [Intent] result from
 *    [MediaProjectionManager.createScreenCaptureIntent]; if it's missing the
 *    service stops itself immediately rather than fabricating capture.
 *  - Registers [MediaProjection.Callback.onStop] so an externally revoked
 *    projection (system dialog, user revoke) safely tears down capture.
 *  - `onDestroy` always releases the [VirtualDisplay], [ImageReader] and
 *    [MediaProjection] to avoid leaks across rotations/kills.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.chineseboot.android.action.START_CAPTURE"
        const val ACTION_STOP = "com.chineseboot.android.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_CHANNEL_ID = "capture_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "ScreenCaptureService"

        /** Downsampling factor applied when converting a captured frame (see [toFrameBuffer]). */
        private const val FRAME_SAMPLE_STRIDE = 4

        /** Minimum spacing between processed frames — chart recognition never needs to run
         *  at full display frame rate, and throttling keeps the overlay/UI thread responsive. */
        private const val MIN_FRAME_INTERVAL_MS = 400L
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var analyzer: ChartCaptureAnalyzer

    @Volatile private var lastProcessedAtMillis = 0L
    private val isProcessingFrame = AtomicBoolean(false)
    private var statusBarHeightPx = 0
    private var navigationBarHeightPx = 0

    private val repository get() = (application as ChineseBootApp).captureRepository

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped externally")
            repository.dispatch(CaptureEvent.ProjectionStoppedExternally)
            stopSelfSafely()
        }
    }

    override fun onCreate() {
        super.onCreate()
        analyzer = ChartCaptureAnalyzer()
        statusBarHeightPx = systemBarHeightPx("status_bar_height")
        navigationBarHeightPx = systemBarHeightPx("navigation_bar_height")
        createNotificationChannel()
    }

    private fun systemBarHeightPx(resourceName: String): Int {
        val id = resources.getIdentifier(resourceName, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

                if (resultCode == Int.MIN_VALUE || resultData == null) {
                    Log.w(TAG, "Missing MediaProjection permission result; not starting capture")
                    repository.dispatch(CaptureEvent.Failure("Screen capture permission result missing"))
                    stopSelfSafely()
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, buildNotification())
                startCapture(resultCode, resultData)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            repository.dispatch(CaptureEvent.Failure("Unable to acquire MediaProjection"))
            stopSelfSafely()
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        val metrics = DisplayMetrics()
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        display?.getRealMetrics(metrics)
        val width = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
        val height = if (metrics.heightPixels > 0) metrics.heightPixels else 1920
        val density = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEFAULT

        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "chinese-boot-capture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )

        reader.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage()
            if (image == null) return@setOnImageAvailableListener

            val now = System.currentTimeMillis()
            val dueForProcessing = now - lastProcessedAtMillis >= MIN_FRAME_INTERVAL_MS
            if (!dueForProcessing || !isProcessingFrame.compareAndSet(false, true)) {
                // Drop this frame — either it's too soon, or a previous frame is still
                // being analyzed. This is the frame-sampling/throttling required to
                // avoid blocking the UI thread and to keep the overlay responsive.
                image.close()
                return@setOnImageAvailableListener
            }
            lastProcessedAtMillis = now

            val frame = try {
                image.toFrameBuffer(now, FRAME_SAMPLE_STRIDE)
            } finally {
                image.close()
            }

            serviceScope.launch {
                try {
                    val excludeRegions = buildExcludeRegions(frame)
                    val result = analyzer.analyze(frame, excludeRegions)
                    repository.publishRecognition(result)
                } finally {
                    isProcessingFrame.set(false)
                }
            }
        }, null)

        repository.dispatch(CaptureEvent.CaptureStarted)
        OverlayService.start(this)
    }

    /**
     * Regions the vision pipeline must ignore: the status bar, the navigation
     * bar, and the app's own floating overlay — all converted into the same
     * downsampled coordinate space as [frame].
     */
    private fun buildExcludeRegions(frame: FrameBuffer): List<PixelRect> {
        val stride = FRAME_SAMPLE_STRIDE
        val regions = mutableListOf<PixelRect>()

        val statusBarScaled = statusBarHeightPx / stride
        if (statusBarScaled > 0) {
            regions += PixelRect(0, 0, frame.width, statusBarScaled)
        }

        val navBarScaled = navigationBarHeightPx / stride
        if (navBarScaled > 0) {
            regions += PixelRect(0, (frame.height - navBarScaled).coerceAtLeast(0), frame.width, navBarScaled)
        }

        repository.overlayBoundsPx.value?.let { overlayBounds ->
            regions += PixelRect(
                x = (overlayBounds.x / stride).coerceAtLeast(0),
                y = (overlayBounds.y / stride).coerceAtLeast(0),
                width = (overlayBounds.width / stride).coerceAtLeast(1),
                height = (overlayBounds.height / stride).coerceAtLeast(1),
            )
        }

        return regions
    }

    private fun stopSelfSafely() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        OverlayService.stop(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.capture_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop_scan), stopPendingIntent)
            .build()
    }
}
