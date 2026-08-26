package com.chineseboot.android.core.vision

/**
 * Enforces a minimum time gap between processed frames so the (relatively
 * expensive) recognition pipeline doesn't run on every single captured
 * frame. Pure/deterministic given an injected clock, so it's trivially
 * unit-testable without real timers.
 */
class FrameThrottler(private val minIntervalMillis: Long) {
    private var lastProcessedMillis: Long? = null

    fun shouldProcess(nowMillis: Long): Boolean {
        val last = lastProcessedMillis
        if (last == null || nowMillis - last >= minIntervalMillis) {
            lastProcessedMillis = nowMillis
            return true
        }
        return false
    }
}
