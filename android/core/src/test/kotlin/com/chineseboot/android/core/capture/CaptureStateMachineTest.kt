package com.chineseboot.android.core.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureStateMachineTest {
    @Test
    fun `full happy path from idle to capturing`() {
        var state = CaptureState.IDLE
        state = CaptureStateMachine.next(state, CaptureEvent.StartRequested)
        assertEquals(CaptureState.AWAITING_SCREEN_PERMISSION, state)

        state = CaptureStateMachine.next(state, CaptureEvent.ScreenPermissionGranted)
        assertEquals(CaptureState.AWAITING_OVERLAY_PERMISSION, state)

        state = CaptureStateMachine.next(state, CaptureEvent.OverlayPermissionGranted)
        assertEquals(CaptureState.CAPTURING, state)
    }

    @Test
    fun `screen permission denial stops the flow`() {
        var state = CaptureState.IDLE
        state = CaptureStateMachine.next(state, CaptureEvent.StartRequested)
        state = CaptureStateMachine.next(state, CaptureEvent.ScreenPermissionDenied)
        assertEquals(CaptureState.STOPPED, state)
    }

    @Test
    fun `overlay permission denial stops the flow`() {
        var state = CaptureState.IDLE
        state = CaptureStateMachine.next(state, CaptureEvent.StartRequested)
        state = CaptureStateMachine.next(state, CaptureEvent.ScreenPermissionGranted)
        state = CaptureStateMachine.next(state, CaptureEvent.OverlayPermissionDenied)
        assertEquals(CaptureState.STOPPED, state)
    }

    @Test
    fun `external projection stop while capturing transitions to stopped`() {
        val state = CaptureStateMachine.next(CaptureState.CAPTURING, CaptureEvent.ProjectionStoppedExternally)
        assertEquals(CaptureState.STOPPED, state)
    }

    @Test
    fun `can restart after being stopped`() {
        var state = CaptureState.STOPPED
        state = CaptureStateMachine.next(state, CaptureEvent.StartRequested)
        assertEquals(CaptureState.AWAITING_SCREEN_PERMISSION, state)
    }

    @Test
    fun `failure moves to error state from any state`() {
        val state = CaptureStateMachine.next(CaptureState.CAPTURING, CaptureEvent.Failure("projection lost"))
        assertEquals(CaptureState.ERROR, state)
    }

    @Test
    fun `stop requested always stops`() {
        val state = CaptureStateMachine.next(CaptureState.AWAITING_OVERLAY_PERMISSION, CaptureEvent.StopRequested)
        assertEquals(CaptureState.STOPPED, state)
    }
}
