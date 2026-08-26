package com.chineseboot.android.core.vision

/** A candidate/selected chart area with a confidence score in [0.0, 1.0]. */
data class ChartRegion(
    val rect: PixelRect,
    val score: Double,
) {
    constructor(x: Int, y: Int, width: Int, height: Int, score: Double) : this(
        PixelRect(x, y, width, height),
        score,
    )

    val x: Int get() = rect.x
    val y: Int get() = rect.y
    val width: Int get() = rect.width
    val height: Int get() = rect.height
    val confidence: Double get() = score
}
