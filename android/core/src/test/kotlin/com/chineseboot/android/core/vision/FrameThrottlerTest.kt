package com.chineseboot.android.core.vision

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameThrottlerTest {
    @Test
    fun `first frame is always processed`() {
        val throttler = FrameThrottler(minIntervalMillis = 500)
        assertTrue(throttler.shouldProcess(0L))
    }

    @Test
    fun `frames within the interval are skipped`() {
        val throttler = FrameThrottler(minIntervalMillis = 500)
        assertTrue(throttler.shouldProcess(0L))
        assertFalse(throttler.shouldProcess(100L))
        assertFalse(throttler.shouldProcess(499L))
    }

    @Test
    fun `frame at or after the interval is processed`() {
        val throttler = FrameThrottler(minIntervalMillis = 500)
        assertTrue(throttler.shouldProcess(0L))
        assertTrue(throttler.shouldProcess(500L))
        assertTrue(throttler.shouldProcess(1200L))
    }
}
