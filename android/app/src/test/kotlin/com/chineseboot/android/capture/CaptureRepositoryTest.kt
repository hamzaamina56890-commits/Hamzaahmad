package com.chineseboot.android.capture

import com.chineseboot.android.core.capture.CaptureEvent
import com.chineseboot.android.core.capture.CaptureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit test (no Android framework needed) verifying that
 * [CaptureRepository] never publishes a stale snapshot once capture stops,
 * per the "never fabricate data" requirement.
 */
class CaptureRepositoryTest {

    @Test
    fun `snapshot is cleared once capture stops`() {
        val repo = CaptureRepository()
        repo.dispatch(CaptureEvent.StartRequested)
        repo.dispatch(CaptureEvent.ScreenPermissionGranted)
        repo.dispatch(CaptureEvent.OverlayPermissionGranted)
        assertEquals(CaptureState.CAPTURING, repo.captureState.value)

        repo.dispatch(CaptureEvent.StopRequested)
        assertEquals(CaptureState.STOPPED, repo.captureState.value)
        assertNull(repo.snapshot.value)
    }

    @Test
    fun `failure event records the error message`() {
        val repo = CaptureRepository()
        repo.dispatch(CaptureEvent.Failure("projection lost"))
        assertEquals("projection lost", repo.errorMessage.value)
        assertEquals(CaptureState.ERROR, repo.captureState.value)
    }
}
