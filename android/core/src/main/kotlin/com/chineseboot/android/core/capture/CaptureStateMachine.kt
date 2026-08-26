package com.chineseboot.android.core.capture

/** States for the screen-capture lifecycle, independent of any Android API. */
enum class CaptureState {
    IDLE,
    AWAITING_SCREEN_PERMISSION,
    AWAITING_OVERLAY_PERMISSION,
    CAPTURING,
    STOPPED,
    ERROR,
}

sealed class CaptureEvent {
    object StartRequested : CaptureEvent()
    object ScreenPermissionGranted : CaptureEvent()
    object ScreenPermissionDenied : CaptureEvent()
    object OverlayPermissionGranted : CaptureEvent()
    object OverlayPermissionDenied : CaptureEvent()
    object CaptureStarted : CaptureEvent()
    object StopRequested : CaptureEvent()
    object ProjectionStoppedExternally : CaptureEvent()
    data class Failure(val reason: String) : CaptureEvent()
}

/**
 * Pure state machine describing the capture lifecycle described in the product
 * spec: permission flow -> capturing -> stop/kill/rotation safe restart.
 * Kept free of Android types so it is trivially unit-testable on the JVM.
 */
object CaptureStateMachine {
    fun next(current: CaptureState, event: CaptureEvent): CaptureState {
        return when (event) {
            is CaptureEvent.StartRequested -> when (current) {
                CaptureState.IDLE, CaptureState.STOPPED, CaptureState.ERROR ->
                    CaptureState.AWAITING_SCREEN_PERMISSION
                else -> current
            }

            is CaptureEvent.ScreenPermissionGranted -> when (current) {
                CaptureState.AWAITING_SCREEN_PERMISSION -> CaptureState.AWAITING_OVERLAY_PERMISSION
                else -> current
            }

            is CaptureEvent.ScreenPermissionDenied -> when (current) {
                CaptureState.AWAITING_SCREEN_PERMISSION -> CaptureState.STOPPED
                else -> current
            }

            is CaptureEvent.OverlayPermissionGranted -> when (current) {
                CaptureState.AWAITING_OVERLAY_PERMISSION -> CaptureState.CAPTURING
                else -> current
            }

            is CaptureEvent.OverlayPermissionDenied -> when (current) {
                CaptureState.AWAITING_OVERLAY_PERMISSION -> CaptureState.STOPPED
                else -> current
            }

            is CaptureEvent.CaptureStarted -> when (current) {
                CaptureState.AWAITING_OVERLAY_PERMISSION -> CaptureState.CAPTURING
                else -> current
            }

            is CaptureEvent.StopRequested -> CaptureState.STOPPED

            is CaptureEvent.ProjectionStoppedExternally -> when (current) {
                CaptureState.CAPTURING -> CaptureState.STOPPED
                else -> current
            }

            is CaptureEvent.Failure -> CaptureState.ERROR
        }
    }
}
