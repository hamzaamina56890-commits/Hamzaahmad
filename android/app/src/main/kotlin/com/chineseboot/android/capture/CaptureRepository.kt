package com.chineseboot.android.capture

import com.chineseboot.android.core.capture.CaptureEvent
import com.chineseboot.android.core.capture.CaptureState
import com.chineseboot.android.core.capture.CaptureStateMachine
import com.chineseboot.android.core.model.AnalysisSnapshot
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
        }
    }

    fun publishSnapshot(snapshot: AnalysisSnapshot) {
        if (_captureState.value == CaptureState.CAPTURING) {
            _snapshot.value = snapshot
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
