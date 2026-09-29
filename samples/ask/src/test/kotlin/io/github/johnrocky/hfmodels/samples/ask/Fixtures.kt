package io.github.johnrocky.hfmodels.samples.ask

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The reference files under src/test/resources/fixtures (the reference drawing of three charts and
 * the turn texts the reference conversation sent), and the app's own assets/charts.json.
 */
object Fixtures {
    fun bytes(name: String): ByteArray =
        requireNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }.use { it.readBytes() }

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    fun json(name: String): Any? = MiniJson(text(name)).parse()

    /** The image's pixels as opaque ARGB, row by row, and its width. */
    fun pixels(name: String): Pair<IntArray, Int> {
        val img: BufferedImage = requireNotNull(ImageIO.read(bytes(name).inputStream())) { "cannot decode $name" }
        val out = IntArray(img.width * img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) out[y * img.width + x] = img.getRGB(x, y) or (0xFF shl 24)
        return out to img.width
    }

    /** assets/charts.json as the app reads it. Unit tests run in the module directory. */
    fun charts(): List<ChartEntry> {
        val file = listOf("src/main/assets/charts.json", "samples/ask/src/main/assets/charts.json").map(::File).firstOrNull { it.isFile }
        return ChartFile.parse(requireNotNull(file) { "assets/charts.json not found from ${File(".").absolutePath}" }.readText())
    }

    /** fixtures/charts.json: the reference record of the first three charts, six questions and six turn texts each. */
    @Suppress("UNCHECKED_CAST")
    fun referenceCharts(): List<Map<String, Any?>> = json("charts.json") as List<Map<String, Any?>>
}
