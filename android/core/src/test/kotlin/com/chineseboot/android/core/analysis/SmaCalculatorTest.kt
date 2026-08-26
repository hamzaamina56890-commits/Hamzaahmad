package com.chineseboot.android.core.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmaCalculatorTest {
    @Test
    fun `computes the average of the last N closes`() {
        val closes = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(4.5, SmaCalculator.compute(closes, 2))
        assertEquals(3.0, SmaCalculator.compute(closes, 5))
    }

    @Test
    fun `returns null when there is not enough history`() {
        assertNull(SmaCalculator.compute(listOf(1.0, 2.0), 5))
    }
}
