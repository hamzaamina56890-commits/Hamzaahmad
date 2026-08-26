package com.chineseboot.android.capture

import com.chineseboot.android.core.capture.CaptureEvent
import com.chineseboot.android.core.capture.CaptureState
import com.chineseboot.android.core.capture.CaptureStateMachine
import com.chineseboot.android.core.model.AnalysisSnapshot
import com.chineseboot.android.core.vision.ChartRecognitionResult
import com.chineseboot.android.core.vision.PixelRect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder for the current capture lifecycle state and the latest
 * [AnalysisSnapshot]. The Activity, [com.chineseboot.android.capture.ScreenCaptureService]
 * and [com.chineseboot.android.overlay.OverlayService] all read/write through this
 * single source of truth so none of them fabricate or duplicate state.
 */
class CaptureRepository {
    private val _captureState = MutableStateFlow(CaptureState.IDLE)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val _snapshot = MutableStateFlow<AnalysisSnapshot?>(null)
    val snapshot: StateFlow<AnalysisSnapshot?> = _snapshot.asStateFlow()

    private val _recognition = MutableStateFlow<ChartRecognitionResult?>(null)
    val recognition: StateFlow<ChartRecognitionResult?> = _recognition.asStateFlow()

    /** Current on-screen bounds of the floating overlay, in full-resolution screen pixels. */
    private val _overlayBoundsPx = MutableStateFlow<PixelRect?>(null)
    val overlayBoundsPx: StateFlow<PixelRect?> = _overlayBoundsPx.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    @Synchronized
    fun dispatch(event: CaptureEvent) {
        _captureState.value = CaptureStateMachine.next(_captureState.value, event)
        if (event is CaptureEvent.Failure) {
            _errorMessage.value = event.reason
        }
        if (_captureState.value != CaptureState.CAPTURING) {
            // Never show a stale analysis result once capture has stopped.
            _snapshot.value = null
            _recognition.value = null
        }
    }

    fun publishSnapshot(snapshot: AnalysisSnapshot) {
        if (_captureState.value == CaptureState.CAPTURING) {
            _snapshot.value = snapshot
        }
    }

    fun publishRecognition(result: ChartRecognitionResult) {
        if (_captureState.value == CaptureState.CAPTURING) {
            _recognition.value = result
        }
    }

    fun updateOverlayBounds(bounds: PixelRect) {
        _overlayBoundsPx.value = bounds
    }

    fun clearError() {
        _errorMessage.value = null
    }
}

