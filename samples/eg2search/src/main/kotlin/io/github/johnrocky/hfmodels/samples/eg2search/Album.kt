package io.github.johnrocky.hfmodels.samples.eg2search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

/**
 * The album is one directory, `album/` in the app's external files dir. Photos added in the app are copies named
 * u<NN>_<name>.jpg; photos pushed with adb keep their own names (the fixture album: a01_red_bicycle.jpg ...), so
 * [removeUserPhotos] takes out the copies and leaves the pushed photos alone.
 */
object Album {
    const val DIR = "album"

    /** The long side of a copy, in pixels: the size of the photos the 2026-10-08 runs searched. */
    const val MAX_SIDE = 1024
    private const val JPEG_QUALITY = 90
    private val USER = Regex("^u(\\d{2,})_.*\\.jpg$")

    class Imported(val files: List<File>, val failed: List<String>, val ms: Double)

    fun dir(context: Context): File =
        File(requireNotNull(context.getExternalFilesDir(null)) { "no external files dir" }, DIR).apply { mkdirs() }

    /** Every .jpg and .jpeg file in [dir], sorted by name. */
    fun photos(dir: File): List<File> = dir.listFiles { f ->
        f.isFile && f.name.lowercase(Locale.US).let { it.endsWith(".jpg") || it.endsWith(".jpeg") }
    }?.sortedBy { it.name }.orEmpty()

    fun isUserPhoto(f: File) = USER.matches(f.name)

    fun userPhotos(dir: File) = photos(dir).filter { isUserPhoto(it) }

    /**
     * Copies the images behind [uris] (the photo picker's result, or any content:// image the app may read) into the
     * album: decoded with ImageDecoder (EXIF rotation applied; JPEG, HEIF, PNG, WebP), shrunk to at most [MAX_SIDE] px on
     * the long side, and saved as JPEG, which carries no EXIF (no location, no camera data). Blocking: call it off the
     * main thread. An image that cannot be read is listed in failed and the others go on; an empty list changes nothing.
     */
    fun importUris(context: Context, uris: List<Uri>): Imported {
        val t0 = System.nanoTime()
        val dir = dir(context)
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        val out = ArrayList<File>()
        val failed = ArrayList<String>()
        var n = nextNumber(dir)
        for (uri in uris) {
            try {
                val bitmap = decode(context, uri)
                val f = File(dir, fileName(n, displayName(context, uri)))
                val tmp = File(dir, f.name + ".tmp")
                try {
                    tmp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) { "JPEG encode failed" } }
                } finally {
                    bitmap.recycle()
                }
                check(tmp.renameTo(f)) { "could not write ${f.name}" }
                out.add(f)
                n++
            } catch (t: Throwable) {
                failed.add("$uri: ${t.message ?: t}")
            }
        }
        return Imported(out, failed, (System.nanoTime() - t0) / 1e6)
    }

    /** Deletes the copies made by [importUris] (u<NN>_*.jpg) and returns how many; the pushed photos stay. */
    fun removeUserPhotos(dir: File): Int = photos(dir).count { isUserPhoto(it) && it.delete() }

    /** One more than the highest u<NN> in [dir], from 1. */
    fun nextNumber(dir: File): Int =
        (photos(dir).mapNotNull { USER.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1

    /** u<NN>_<name>.jpg: the picked file's name without its extension, lower case, other characters as "-". */
    fun fileName(n: Int, displayName: String?): String {
        val base = (displayName ?: "").substringBeforeLast('.').lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-')
        return String.format(Locale.US, "u%02d_%s.jpg", n, base.ifEmpty { "photo" })
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun decode(context: Context, uri: Uri): Bitmap {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            // Software pixels, so the copy can be scaled and encoded; a power-of-two subsample on the way in keeps a
            // 50-megapixel photo from being decoded at full size (the long side stays at MAX_SIDE or more).
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (long / (sample * 2) >= MAX_SIDE) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
        val long = maxOf(decoded.width, decoded.height)
        if (long <= MAX_SIDE) return decoded
        val s = MAX_SIDE.toDouble() / long
        val scaled = Bitmap.createScaledBitmap(decoded, maxOf(1, Math.round(decoded.width * s).toInt()),
            maxOf(1, Math.round(decoded.height * s).toInt()), true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}
