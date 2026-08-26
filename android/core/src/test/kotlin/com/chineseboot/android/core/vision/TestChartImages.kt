package com.chineseboot.android.core.vision

/**
 * TEST FIXTURES ONLY — synthetic images generated purely to exercise the
 * chart/candle detectors deterministically. None of these pixels represent
 * real market data; they are simple geometric drawings.
 */
object TestChartImages {
    /** A uniformly colored frame with no chart-like structure at all. */
    fun blankFrame(width: Int = 60, height: Int = 40, color: Int = ColorUtils.rgb(20, 20, 20)): FrameBuffer {
        val pixels = IntArray(width * height) { color }
        return FrameBuffer(width, height, pixels, timestampMillis = 1L)
    }

    /**
     * Draws a row of evenly spaced candlesticks against [background].
     * Each candle: a thin wick (1px wide, centered) spanning [wickTop, wickBottom]
     * and a wider body spanning [bodyTop, bodyBottom], alternating bullishColor/
     * bearishColor. Returns the frame plus the candle x-centers used, for assertions.
     */
    fun candlestickChart(
        width: Int = 120,
        height: Int = 60,
        candleCount: Int = 10,
        candleWidth: Int = 5,
        spacing: Int = 3,
        bodyTop: Int = 20,
        bodyBottom: Int = 40,
        wickTop: Int = 12,
        wickBottom: Int = 48,
        background: Int = ColorUtils.rgb(255, 255, 255),
        bullishColor: Int = ColorUtils.rgb(0, 180, 0),
        bearishColor: Int = ColorUtils.rgb(200, 0, 0),
        marginLeft: Int = 10,
    ): FrameBuffer {
        val pixels = IntArray(width * height) { background }
        fun set(x: Int, y: Int, color: Int) {
            if (x in 0 until width && y in 0 until height) pixels[y * width + x] = color
        }

        var x = marginLeft
        for (i in 0 until candleCount) {
            val color = if (i % 2 == 0) bullishColor else bearishColor
            val centerX = x + candleWidth / 2
            for (y in wickTop until wickBottom) set(centerX, y, color)
            for (dx in 0 until candleWidth) {
                for (y in bodyTop until bodyBottom) set(x + dx, y, color)
            }
            x += candleWidth + spacing
        }
        return FrameBuffer(width, height, pixels, timestampMillis = 42L)
    }

    /** A frame containing only a small irregular noise blob (simulating an icon/button), not a chart. */
    fun noiseBlobFrame(width: Int = 60, height: Int = 40, background: Int = ColorUtils.rgb(255, 255, 255)): FrameBuffer {
        val pixels = IntArray(width * height) { background }
        fun set(x: Int, y: Int, color: Int) {
            if (x in 0 until width && y in 0 until height) pixels[y * width + x] = color
        }
        // A single irregular blob: not periodic, not thin/repeated.
        for (y in 10 until 18) {
            for (x in 20 until 30) {
                if ((x + y) % 3 != 0) set(x, y, ColorUtils.rgb(50, 50, 200))
            }
        }
        return FrameBuffer(width, height, pixels, timestampMillis = 7L)
    }
}
