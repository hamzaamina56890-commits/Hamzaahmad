package com.chineseboot.android.core.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PriceLabelParserTest {
    @Test
    fun `parses a plain decimal price`() {
        val parsed = PriceLabelParser.parse("1.2345")
        assertEquals(1.2345, parsed!!.value, 1e-9)
        assertEquals(4, parsed.decimalPlaces)
    }

    @Test
    fun `parses a price with thousands separators`() {
        val parsed = PriceLabelParser.parse("1,234.5")
        assertEquals(1234.5, parsed!!.value, 1e-9)
    }

    @Test
    fun `parses an integer-looking price with zero decimal places`() {
        val parsed = PriceLabelParser.parse("108")
        assertEquals(108.0, parsed!!.value, 1e-9)
        assertEquals(0, parsed.decimalPlaces)
    }

    @Test
    fun `rejects blank text`() {
        assertNull(PriceLabelParser.parse(""))
        assertNull(PriceLabelParser.parse("   "))
    }

    @Test
    fun `rejects non-numeric OCR garbage`() {
        assertNull(PriceLabelParser.parse("BUY"))
        assertNull(PriceLabelParser.parse("---"))
        assertNull(PriceLabelParser.parse("BUY 1.2345"))
        assertNull(PriceLabelParser.parse("1.2345 USD"))
        assertNull(PriceLabelParser.parse("1,23.45"))
    }

    @Test
    fun `rejects zero or negative prices`() {
        assertNull(PriceLabelParser.parse("0"))
        assertNull(PriceLabelParser.parse("-5.0"))
    }
}
