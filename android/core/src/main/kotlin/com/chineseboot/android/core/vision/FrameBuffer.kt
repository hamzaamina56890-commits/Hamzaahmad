package com.chineseboot.android.core.vision

/** Axis-aligned pixel rectangle used throughout the vision pipeline. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun overlaps(other: PixelRect): Boolean =
        x < other.right && other.x < right && y < other.bottom && other.y < bottom

    fun contains(px: Int, py: Int): Boolean =
        px in x until right && py in y until bottom
}

/**
 * A single captured frame as a raw pixel buffer. Intentionally free of any
 * Android type (no `Bitmap`/`Image`) so the whole vision pipeline is a plain
 * Kotlin/JVM library that can be unit tested without an emulator. The `app`
 * module is responsible for converting a MediaProjection `Image` into this.
 */
data class FrameBuffer(
    val width: Int,
    val height: Int,
    /** Row-major ARGB_8888 pixels, size must be exactly width*height. */
    val pixels: IntArray,
    val timestampMillis: Long,
) {
    val argb: IntArray get() = pixels

    val isEmpty: Boolean get() = width <= 0 || height <= 0 || pixels.isEmpty()

    init {
        require(pixels.size == width * height) {
            "pixels size ${pixels.size} does not match ${width}x$height"
        }
    }

    fun pixelAt(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        return pixels[y * width + x]
    }

    operator fun get(x: Int, y: Int): Int = pixelAt(x, y)

    fun luminanceAt(x: Int, y: Int): Int {
        val p = pixelAt(x, y)
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    fun rgbAt(x: Int, y: Int): Triple<Int, Int, Int> {
        val p = pixelAt(x, y)
        return Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    }

    fun downsample(factor: Int): FrameBuffer {
        if (factor <= 1) return this
        val targetWidth = (width / factor).coerceAtLeast(1)
        val targetHeight = (height / factor).coerceAtLeast(1)
        val newPixels = IntArray(targetWidth * targetHeight)
        var idx = 0
        for (y in 0 until targetHeight) {
            val srcY = y * factor
            for (x in 0 until targetWidth) {
                val srcX = x * factor
                newPixels[idx++] = pixelAt(srcX, srcY)
            }
        }
        return FrameBuffer(targetWidth, targetHeight, newPixels, timestampMillis)
    }

    fun withMaskedRegions(regions: List<PixelRect>, fillColor: Int): FrameBuffer {
        if (regions.isEmpty()) return this
        val newPixels = pixels.copyOf()
        for (region in regions) {
            val startX = region.x.coerceIn(0, width)
            val endX = region.right.coerceIn(0, width)
            val startY = region.y.coerceIn(0, height)
            val endY = region.bottom.coerceIn(0, height)
            for (y in startY until endY) {
                val rowOffset = y * width
                for (x in startX until endX) {
                    newPixels[rowOffset + x] = fillColor
                }
            }
        }
        return FrameBuffer(width, height, newPixels, timestampMillis)
    }

    override fun equals(other: Any?): Boolean =
        other is FrameBuffer && width == other.width && height == other.height &&
            timestampMillis == other.timestampMillis && pixels.contentEquals(other.pixels)

    override fun hashCode(): Int =
        (width * 31 + height) * 31 + pixels.contentHashCode()
}
