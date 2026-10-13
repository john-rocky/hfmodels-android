package io.github.johnrocky.hfmodels.samples.look

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the screen refuses before the model, the examples, the file a phone loads, and the size of the photo it sends. */
class TypedTest {
    @Test
    fun refusalsComeBeforeTheModel() {
        assertEquals(Typed.Result.Refused(Typed.NO_PHOTO), Typed.check(false, "What is this?"))
        assertEquals(Typed.Result.Refused(Typed.NO_QUESTION), Typed.check(true, ""))
        assertEquals(Typed.Result.Refused(Typed.NO_QUESTION), Typed.check(true, "  \n "))
        assertEquals(Typed.Result.Refused(Typed.TOO_LONG), Typed.check(true, "x".repeat(Typed.MAX_QUESTION_CHARS + 1)))
        // No photo is the first thing said, whatever the question.
        assertEquals(Typed.Result.Refused(Typed.NO_PHOTO), Typed.check(false, ""))
    }

    @Test
    fun readyIsTrimmed() {
        assertEquals(Typed.Result.Ready("What does this sign say?"), Typed.check(true, "  What does this sign say? \n"))
        assertEquals(Typed.Result.Ready("x".repeat(Typed.MAX_QUESTION_CHARS)), Typed.check(true, "x".repeat(Typed.MAX_QUESTION_CHARS)))
    }

    @Test
    fun theExampleQuestionsAreAcceptedAsTheyAre() {
        assertEquals(listOf("What does this sign say?", "What is this and how do I use it?", "Describe this scene in one sentence"), Examples.QUESTIONS)
        for (q in Examples.QUESTIONS) assertEquals(Typed.Result.Ready(q), Typed.check(true, q))
        assertEquals(listOf("sign", "object", "scene"), Examples.PHOTOS.map { it.id })
        // Each example photo comes with its own chip's question, in the chips' order.
        assertEquals(Examples.QUESTIONS, Examples.PHOTOS.map { it.question })
        for (e in Examples.PHOTOS) assertTrue(e.asset, e.asset.startsWith("examples/"))
        assertEquals(Examples.PHOTOS.first(), Examples.photo("sign"))
        assertEquals(null, Examples.photo("no-such-example"))
    }

    @Test
    fun theFetchListNamesTheSamePhotos() {
        // tools/examples.json is what tools/fetch_examples.py downloads into assets/examples/; the app reads Examples.
        val json = listOf(File("tools/examples.json"), File("samples/look/tools/examples.json")).first { it.exists() }.readText()
        fun values(key: String) = Regex("\"$key\":\\s*\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(Examples.PHOTOS.map { it.id }, values("id"))
        assertEquals(Examples.PHOTOS.map { it.asset.removePrefix("examples/") }, values("file"))
        assertEquals(Examples.PHOTOS.map { it.question }, values("question"))
        assertEquals(3, values("sha256").count { it.matches(Regex("[0-9a-f]{64}")) })
        assertEquals(List(3) { "CC0 1.0" }, values("license"))
    }

    @Test
    fun theFileFollowsThePhonesMemory() {
        // MemTotal of the phones measured on the card: Pixel 8a 7,754,700 kB, Galaxy S26 11,389,756 kB.
        assertEquals(Variants.VL_INT8VIS, Variants.forMemory(7_754_700L * 1024))
        assertEquals(Variants.VL_INT8, Variants.forMemory(11_389_756L * 1024))
        assertEquals(Variants.VL_INT8, Variants.forMemory(10L shl 30))
        assertEquals(Variants.VL_INT8VIS, Variants.forMemory((10L shl 30) - 1))
    }

    @Test
    fun photosAreScaledToTheLongSide() {
        assertEquals(768 to 576, PhotoImport.fit(6000, 4500))
        // The examples: the sign 1024 x 768, the chess pieces 960 x 640, the lighthouse 1024 x 683.
        assertEquals(768 to 576, PhotoImport.fit(1024, 768))
        assertEquals(768 to 512, PhotoImport.fit(960, 640))
        assertEquals(768 to 512, PhotoImport.fit(1024, 683))
        assertEquals(576 to 768, PhotoImport.fit(3024, 4032))
        // Never up.
        assertEquals(500 to 300, PhotoImport.fit(500, 300))
        assertEquals(4, PhotoImport.sampleSize(6000, 4500))
        assertEquals(1, PhotoImport.sampleSize(1024, 768))
    }
}
