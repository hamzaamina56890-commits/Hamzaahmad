package com.chineseboot.android.core.vision

/**
 * Resolves the candle timeframe from visible chart text. Candle pixel
 * spacing is deliberately *not* used to invent a timeframe — per spec it may
 * only serve as a consistency check once a timeframe is already known from
 * readable text.
 */
object TimeframeCalibrator {
    /** seconds -> accepted label spellings. */
    private val LABEL_ALIASES: Map<Int, List<String>> = mapOf(
        5 to listOf("5s", "5sec", "5 sec", "5secs", "s5"),
        10 to listOf("10s", "10sec", "10 sec", "10secs"),
        15 to listOf("15s", "15sec", "15 sec", "15secs"),
        30 to listOf("30s", "30sec", "30 sec", "30secs"),
        60 to listOf("1m", "1min", "1 min", "m1", "1minute"),
        120 to listOf("2m", "2min", "2 min", "m2", "2minutes"),
        180 to listOf("3m", "3min", "3 min", "m3", "3minutes"),
        300 to listOf("5m", "5min", "5 min", "m5", "5minutes"),
    )

    private val NUMERIC_UNIT = Regex("""^(\d+)\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes)$""")

    /**
     * Parses visible timeframe text (e.g. "1m", "30s", "5 min") into seconds.
     * Returns `null` (TIMEFRAME_UNKNOWN) whenever the text isn't a confident,
     * supported match — never a guess.
     */
    fun fromLabel(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val normalized = text.trim().lowercase()

        for ((seconds, aliases) in LABEL_ALIASES) {
            if (aliases.any { it == normalized }) return seconds
        }

        val match = NUMERIC_UNIT.find(normalized) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2]
        val seconds = if (unit.startsWith("s")) amount else amount * 60
        return if (seconds in SupportedTimeframes.SECONDS) seconds else null
    }

    /**
     * Consistency check only: given candle centers and an already-known
     * timeframe, returns how uniform the observed pixel spacing is (0.0-1.0).
     * A low score means the visible candles do not look like a clean,
     * evenly-spaced series of that timeframe — useful as a corroborating
     * signal, never as the sole source of a timeframe value.
     */
    fun spacingConsistency(candles: List<PixelCandle>): Double {
        if (candles.size < 3) return 0.0
        val sorted = candles.sortedBy { it.xPosition }
        val spacings = sorted.zipWithNext { a, b -> (b.xPosition - a.xPosition).toDouble() }
            .filter { it > 0 }
        if (spacings.size < 2) return 0.0

        val mean = spacings.average()
        if (mean <= 0.0) return 0.0
        val variance = spacings.sumOf { (it - mean) * (it - mean) } / spacings.size
        val stddev = kotlin.math.sqrt(variance)
        val coefficientOfVariation = stddev / mean
        return (1.0 - coefficientOfVariation).coerceIn(0.0, 1.0)
    }

    /**
     * Resolves a final timeframe: prefers readable on-chart text; otherwise
     * returns `null` (TIMEFRAME_UNKNOWN) rather than inferring one from
     * spacing alone.
     */
    fun resolve(labelText: String?, fallbackTimeframeSeconds: Int? = null): Int? =
        fromLabel(labelText) ?: fallbackTimeframeSeconds
}
