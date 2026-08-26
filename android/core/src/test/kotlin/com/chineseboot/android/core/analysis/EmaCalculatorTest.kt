package com.chineseboot.android.core.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmaCalculatorTest {
    @Test
    fun `computes a known EMA value seeded from an initial SMA`() {
        val closes = (1..10).map { it.toDouble() }
        // period 3: seed = avg(1,2,3) = 2.0, then each subsequent step moves
        // exactly halfway towards the new close (multiplier = 2/(3+1) = 0.5).
        val ema = EmaCalculator.compute(closes, 3)
        assertTrue(ema != null)
        assertEquals(9.0, ema!!, 1e-9)
    }

    @Test
    fun `reacts faster than a long-window SMA after a sudden price jump`() {
        val closes = List(20) { 10.0 } + List(10) { 20.0 }
        val ema9 = EmaCalculator.compute(closes, 9)!!
        val sma20 = SmaCalculator.compute(closes, 20)!!
        // After the jump, EMA-9 (short window) has moved further toward the
        // new price than the much longer SMA-20 trailing average.
        assertTrue(ema9 > sma20)
    }

    @Test
    fun `returns null when there is not enough history`() {
        assertNull(EmaCalculator.compute(listOf(1.0, 2.0), 9))
    }
}
