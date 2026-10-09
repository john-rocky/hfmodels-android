package io.github.johnrocky.hfmodels.samples.ask

import org.junit.Assert.assertEquals
import org.junit.Test

/** Your own question: what the screen refuses before the model, the turn texts it sends, the size of the photo. */
class TypedTest {
    private val two = listOf("a cat", "a dog", "", "", "")

    @Test
    fun refusalsComeBeforeTheModel() {
        assertEquals(Typed.Result.Refused(Typed.NO_PHOTO), Typed.check(false, "What is this?", two))
        assertEquals(Typed.Result.Refused(Typed.NO_QUESTION), Typed.check(true, "   ", two))
        assertEquals(Typed.Result.Refused(Typed.TOO_FEW), Typed.check(true, "What is this?", listOf("a cat", "", "", "", "")))
        assertEquals(Typed.Result.Refused(Typed.TOO_FEW), Typed.check(true, "What is this?", listOf(" ", "", "", "", "")))
        // An empty box between two filled ones would shift the letters the model reads away from the screen's.
        assertEquals(Typed.Result.Refused(Typed.GAP), Typed.check(true, "What is this?", listOf("a cat", "", "a dog", "", "")))
        assertEquals(Typed.Result.Refused(Typed.SAME), Typed.check(true, "What is this?", listOf("a cat", " A cat", "", "", "")))
        assertEquals(Typed.Result.Refused(Typed.TOO_MANY), Typed.check(true, "What is this?", listOf("a", "b", "c", "d", "e", "f")))
    }

    @Test
    fun readyIsTrimmedInLetterOrder() {
        assertEquals(Typed.Result.Ready("What is this?", listOf("a cat", "a dog")), Typed.check(true, "  What is this? ", listOf(" a cat", "a dog ", "", " ", "")))
        val five = listOf("2", "3", "4", "5", "6")
        assertEquals(Typed.Result.Ready("How many?", five), Typed.check(true, "How many?", five))
    }

    @Test
    fun aPhotoTurnHasTheChartTurnShape() {
        // Every chart carries the same context line, so a photo's first turn differs from a chart's only in the question.
        for (c in Fixtures.charts()) assertEquals(Prompt.CONTEXT, c.spec.context)
        val spec = Fixtures.charts()[0].spec
        val q = Questions.of(spec)[0]
        assertEquals(Prompt.first(spec.context, q), Prompt.first(Prompt.CONTEXT, q.text, q.options))
        assertEquals(
            "Context:\nThis is a visual question about the image.\n\nQuestion: What is this a photo of?\nOptions:\n(A) a car\n(B) a boat\n(C) a bicycle\nAnswer: (",
            Prompt.first(Prompt.CONTEXT, "What is this a photo of?", listOf("a car", "a boat", "a bicycle")),
        )
        assertEquals(
            ")\n\nQuestion: What colour is the bicycle?\nOptions:\n(A) blue\n(B) red\n(C) green\nAnswer: (",
            Prompt.next("What colour is the bicycle?", listOf("blue", "red", "green")),
        )
    }

    @Test
    fun photosAreScaledToTheLongSide() {
        assertEquals(768 to 576, PhotoImport.fit(6000, 4500))
        assertEquals(768 to 547, PhotoImport.fit(1023, 728))
        assertEquals(576 to 768, PhotoImport.fit(3024, 4032))
        assertEquals(768 to 1, PhotoImport.fit(10_000, 1))
        // Never up.
        assertEquals(500 to 300, PhotoImport.fit(500, 300))
        assertEquals(768 to 768, PhotoImport.fit(768, 768))
        // Decoding subsamples by powers of two while the long side stays at or above 768.
        assertEquals(4, PhotoImport.sampleSize(6000, 4500))
        assertEquals(2, PhotoImport.sampleSize(1536, 100))
        assertEquals(1, PhotoImport.sampleSize(1023, 728))
        assertEquals(1, PhotoImport.sampleSize(300, 200))
    }
}
