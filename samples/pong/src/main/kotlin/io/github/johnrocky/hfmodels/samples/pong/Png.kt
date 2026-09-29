package io.github.johnrocky.hfmodels.samples.pong

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/** Opaque ARGB pixels to PNG bytes (lossless). These bytes are what the model gets and what the record keeps. */
object Png {
    fun encode(pixels: IntArray, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setHasAlpha(false)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
