package com.chineseboot.android.core.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Small ARGB color-math helpers used by the chart/candle detectors. */
object ColorUtils {
    fun red(color: Int): Int = (color shr 16) and 0xFF
    fun green(color: Int): Int = (color shr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF

    fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Euclidean distance in RGB space, 0..~441. */
    fun distance(a: Int, b: Int): Double {
        val dr = red(a) - red(b)
        val dg = green(a) - green(b)
        val db = blue(a) - blue(b)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    fun luminance(color: Int): Double =
        0.299 * red(color) + 0.587 * green(color) + 0.114 * blue(color)

    /** Hue in degrees [0, 360). Returns 0.0 for achromatic (gray/black/white) colors. */
    fun hueDegrees(color: Int): Double {
        val r = red(color) / 255.0
        val g = green(color) / 255.0
        val b = blue(color) / 255.0
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        if (delta < 1e-6) return 0.0
        val hue = when (maxC) {
            r -> 60.0 * (((g - b) / delta) % 6.0)
            g -> 60.0 * (((b - r) / delta) + 2.0)
            else -> 60.0 * (((r - g) / delta) + 4.0)
        }
        return if (hue < 0) hue + 360.0 else hue
    }

    /** Saturation in [0, 1]; low saturation means the color is close to gray/white/black. */
    fun saturation(color: Int): Double {
        val r = red(color) / 255.0
        val g = green(color) / 255.0
        val b = blue(color) / 255.0
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        if (maxC < 1e-6) return 0.0
        return (maxC - minC) / maxC
    }

    fun averageColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0
        var r = 0L; var g = 0L; var b = 0L
        for (c in colors) { r += red(c); g += green(c); b += blue(c) }
        val n = colors.size
        return rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
