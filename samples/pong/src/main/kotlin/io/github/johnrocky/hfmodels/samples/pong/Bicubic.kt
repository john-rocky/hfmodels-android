package io.github.johnrocky.hfmodels.samples.pong

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Bicubic resize of opaque ARGB pixels, computed the way Pillow's `Image.resize(size, Image.BICUBIC)`
 * does for an 8-bit RGB image, so the phone sends the model the pixels the reference pipeline produced:
 * a separable Keys kernel (a = -0.5, support 2, widened by the scale when shrinking), taps at the source
 * pixel centres around `(dst + 0.5) * scale`, weights normalized per output pixel and held as 22-bit
 * fixed point, the horizontal pass first into an 8-bit image, then the vertical pass.
 */
object Bicubic {
    private const val PRECISION_BITS = 32 - 8 - 2

    private fun kernel(v: Double): Double {
        val a = -0.5
        val x = abs(v)
        return when {
            x < 1.0 -> ((a + 2.0) * x - (a + 3.0)) * x * x + 1
            x < 2.0 -> (((x - 5) * x + 8) * x - 4) * a
            else -> 0.0
        }
    }

    /** Per output pixel: the first source index, the tap count, and the fixed-point weights. */
    private class Taps(val size: Int, val first: IntArray, val count: IntArray, val weights: IntArray)

    private fun taps(inSize: Int, outSize: Int): Taps {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = 2.0 * filterScale
        val size = ceil(support).toInt() * 2 + 1
        val first = IntArray(outSize)
        val count = IntArray(outSize)
        val weights = IntArray(outSize * size)
        val w = DoubleArray(size)
        val ss = 1.0 / filterScale
        for (o in 0 until outSize) {
            val center = (o + 0.5) * scale
            val xmin = max((center - support + 0.5).toInt(), 0)
            val xmax = minOf((center + support + 0.5).toInt(), inSize) - xmin
            var sum = 0.0
            for (x in 0 until xmax) {
                w[x] = kernel((x + xmin - center + 0.5) * ss)
                sum += w[x]
            }
            for (x in 0 until xmax) {
                val k = if (sum != 0.0) w[x] / sum else w[x]
                weights[o * size + x] = if (k < 0) (-0.5 + k * (1 shl PRECISION_BITS)).toInt() else (0.5 + k * (1 shl PRECISION_BITS)).toInt()
            }
            first[o] = xmin
            count[o] = xmax
        }
        return Taps(size, first, count, weights)
    }

    private fun clip8(v: Int): Int = when {
        v >= (1 shl PRECISION_BITS shl 8) -> 255
        v <= 0 -> 0
        else -> v shr PRECISION_BITS
    }

    fun resize(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        require(src.size == srcW * srcH) { "src has ${src.size} pixels, not $srcW x $srcH" }
        val h = taps(srcW, dstW)
        val tmp = IntArray(dstW * srcH)
        val half = 1 shl (PRECISION_BITS - 1)
        for (y in 0 until srcH) {
            val row = y * srcW
            for (o in 0 until dstW) {
                var r = half; var g = half; var b = half
                val x0 = h.first[o]
                for (i in 0 until h.count[o]) {
                    val p = src[row + x0 + i]
                    val k = h.weights[o * h.size + i]
                    r += ((p shr 16) and 0xFF) * k
                    g += ((p shr 8) and 0xFF) * k
                    b += (p and 0xFF) * k
                }
                tmp[y * dstW + o] = (0xFF shl 24) or (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
            }
        }
        val v = taps(srcH, dstH)
        val out = IntArray(dstW * dstH)
        for (o in 0 until dstH) {
            val y0 = v.first[o]
            for (x in 0 until dstW) {
                var r = half; var g = half; var b = half
                for (i in 0 until v.count[o]) {
                    val p = tmp[(y0 + i) * dstW + x]
                    val k = v.weights[o * v.size + i]
                    r += ((p shr 16) and 0xFF) * k
                    g += ((p shr 8) and 0xFF) * k
                    b += (p and 0xFF) * k
                }
                out[o * dstW + x] = (0xFF shl 24) or (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
            }
        }
        return out
    }
}
