package io.github.johnrocky.hfmodels.samples.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the screen refuses before the model, the file a phone loads, and the size of the photo it sends. */
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
        assertEquals(3, Examples.QUESTIONS.size)
        for (q in Examples.QUESTIONS) assertEquals(Typed.Result.Ready(q), Typed.check(true, q))
        for (e in Examples.PHOTOS) {
            assertEquals(Typed.Result.Ready(e.question), Typed.check(true, e.question))
            assertTrue(e.asset, e.asset.startsWith("examples/"))
        }
        assertEquals(Examples.PHOTOS.first(), Examples.next(null))
        assertEquals(Examples.PHOTOS.first(), Examples.next(Examples.PHOTOS.last()))
        assertEquals(null, Examples.photo("no-such-example"))
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
        assertEquals(768 to 547, PhotoImport.fit(1023, 728))
        assertEquals(576 to 768, PhotoImport.fit(3024, 4032))
        // Never up.
        assertEquals(500 to 300, PhotoImport.fit(500, 300))
        assertEquals(4, PhotoImport.sampleSize(6000, 4500))
        assertEquals(1, PhotoImport.sampleSize(1023, 728))
    }
}
