package com.chineseboot.android.core.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RsiCalculatorTest {
    @Test
    fun `returns null when not enough history`() {
        val closes = List(10) { 1.0 + it * 0.01 }
        assertNull(RsiCalculator.compute(closes, period = 14))
    }

    @Test
    fun `returns 100 when there are no losses`() {
        val closes = (0..20).map { 1.0 + it * 0.01 }
        val rsi = RsiCalculator.compute(closes, period = 14)
        assertEquals(100.0, rsi)
    }

    @Test
    fun `returns a value between 0 and 100 for mixed data`() {
        val closes = listOf(1.0, 1.01, 0.99, 1.02, 1.0, 0.98, 1.03, 1.05, 1.02, 1.01, 1.04, 1.06, 1.03, 1.02, 1.05, 1.07)
        val rsi = RsiCalculator.compute(closes, period = 14)
        assertTrue(rsi != null && rsi in 0.0..100.0)
    }
}
