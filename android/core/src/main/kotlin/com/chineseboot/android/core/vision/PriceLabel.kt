package com.chineseboot.android.core.vision

/**
 * A single price-axis label as read directly from the chart (e.g. via OCR).
 * [pixelY] is the label's vertical center in the captured frame; [rawText] is
 * whatever text was recognized (before numeric parsing); [ocrConfidence] is
 * the recognizer's own confidence in [0.0, 1.0].
 */
data class DetectedPriceLabel(
    val pixelY: Int,
    val rawText: String,
    val ocrConfidence: Double,
)

/** A price label's text successfully parsed into a numeric value. */
data class ParsedPrice(val value: Double, val decimalPlaces: Int)

/**
 * Parses raw OCR text from a chart price label into a numeric value.
 * Never guesses: text that doesn't look like a plausible price returns `null`.
 */
object PriceLabelParser {
    private val NUMERIC_PATTERN = Regex("""-?\d[\d,]*(?:\.\d+)?""")

    fun parse(rawText: String): ParsedPrice? {
        val cleaned = rawText.trim()
        if (cleaned.isEmpty()) return null

        val match = NUMERIC_PATTERN.find(cleaned) ?: return null
        val numericText = match.value.replace(",", "")
        if (numericText.isEmpty() || numericText == "-") return null

        val value = numericText.toDoubleOrNull() ?: return null
        // Reject obviously-invalid OCR reads (negative or zero-ish chart prices).
        if (value <= 0.0) return null

        val dotIndex = numericText.indexOf('.')
        val decimalPlaces = if (dotIndex == -1) 0 else numericText.length - dotIndex - 1

        return ParsedPrice(value, decimalPlaces)
    }
}
