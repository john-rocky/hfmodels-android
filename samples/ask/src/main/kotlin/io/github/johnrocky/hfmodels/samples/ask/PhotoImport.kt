package io.github.johnrocky.hfmodels.samples.ask

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** A picked photo as the model gets it: JPEG [bytes], [width] x [height], from a [sourceWidth] x [sourceHeight] original. */
class Photo(val bytes: ByteArray, val width: Int, val height: Int, val sourceWidth: Int, val sourceHeight: Int)

/**
 * The photo picker's result to the bytes the model gets. The original is decoded with its EXIF rotation
 * applied, subsampled while it decodes (a 50-megapixel photo never sits in memory at full size), scaled
 * so its long side is at most [MAX_SIDE] (never up), and encoded as JPEG. The screen shows these bytes.
 */
object PhotoImport {
    const val MAX_SIDE = 768
    const val QUALITY = 90

    /** Blocking: call it off the main thread. Throws when [uri] cannot be read or decoded. */
    fun load(resolver: ContentResolver, uri: Uri, maxSide: Int = MAX_SIDE): Photo {
        var sourceW = 0
        var sourceH = 0
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            sourceW = info.size.width
            sourceH = info.size.height
            decoder.setTargetSampleSize(sampleSize(sourceW, sourceH, maxSide))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val (w, h) = fit(decoded.width, decoded.height, maxSide)
        val scaled = if (w == decoded.width && h == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, w, h, true)
        // JPEG has no alpha: a transparent pixel would turn black, so the picture goes onto white first.
        val opaque = if (!scaled.hasAlpha()) scaled else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(0xFFFFFFFF.toInt()); drawBitmap(scaled, 0f, 0f, null) }
        }
        try {
            val out = ByteArrayOutputStream()
            check(opaque.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) { "JPEG encoding failed" }
            return Photo(out.toByteArray(), w, h, sourceW, sourceH)
        } finally {
            for (b in setOf(decoded, scaled, opaque)) b.recycle()
        }
    }

    /** The size whose long side is at most [maxSide], aspect ratio kept; the size itself when it already fits. */
    fun fit(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "empty image ${width}x$height" }
        val long = max(width, height)
        if (long <= maxSide) return width to height
        val k = maxSide.toDouble() / long
        return max(1, (width * k).roundToInt()).coerceAtMost(maxSide) to max(1, (height * k).roundToInt()).coerceAtMost(maxSide)
    }

    /** The largest power of two that keeps the long side at or above [maxSide] while decoding. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        val long = max(width, height)
        var s = 1
        while (long / (s * 2) >= maxSide) s *= 2
        return s
    }
}
