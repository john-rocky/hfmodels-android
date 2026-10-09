package io.github.johnrocky.hfmodels.samples.decide.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The generator on the JVM, no model: counts, determinism, no repeats, no empty text, only invented names. */
class SmsGeneratorTest {
    // Unit tests run in the module directory.
    private val names: Set<String> = File("NAMES.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
    private val v = SmsVocabulary.DEFAULT

    @Test fun givesTheRequestedCount() {
        for (n in listOf(0, 1, 24, 300, 1000)) assertEquals(n, SmsGenerator.generate(n, 7).size)
    }

    @Test fun sameSeedSameTexts() {
        assertEquals(SmsGenerator.generate(300, 7), SmsGenerator.generate(300, 7))
        assertNotEquals(SmsGenerator.generate(300, 7).map { it.text }, SmsGenerator.generate(300, 8).map { it.text })
    }

    @Test fun noRepeatedText() {
        for (seed in 1L..5L) {
            val texts = SmsGenerator.generate(1000, seed).map { it.text }
            assertEquals(texts.size, texts.toSet().size)
        }
    }

    @Test fun noEmptyTextAndNoOpenSlot() {
        for (m in SmsGenerator.generate(1000, 7)) {
            assertTrue(m.text, m.text.isNotBlank() && '{' !in m.text && '}' !in m.text)
        }
    }

    @Test fun everySenderIsInvented() {
        for (m in SmsGenerator.generate(1000, 7)) {
            assertTrue("sender ${m.from}", m.from in names || SmsGenerator.isFictionalNumber(m.from))
        }
    }

    @Test fun everyVocabularyNameIsListed() {
        val used = v.brands.values.flatten().flatMap { listOf(it.sender, it.name) } + v.people + v.surnames + v.pets
        for (n in used) assertTrue("$n is missing from NAMES.txt", n in names)
        for (b in v.brands.values.flatten()) assertTrue("sender id ${b.sender} is longer than 11", b.sender.length <= 11)
    }

    @Test fun linksNeverResolve() {
        for (m in SmsGenerator.generate(1000, 7)) {
            for (link in Regex("""https?://\S+""").findAll(m.text)) assertTrue(link.value, link.value.endsWith(".example"))
        }
    }

    @Test fun newestFirstWithinFourteenDays() {
        val ms = SmsGenerator.generate(300, 7)
        assertTrue(ms.zipWithNext().all { (a, b) -> a.minutesAgo <= b.minutesAgo })
        assertTrue(ms.first().minutesAgo >= 0 && ms.last().minutesAgo < 14 * 24 * 60)
    }

    @Test fun aKindLeftOutOfTheMixNeverAppears() {
        val noScams = v.copy(mix = v.mix - "scam")
        val ms = SmsGenerator.generate(300, 7, noScams)
        assertEquals(300, ms.size)
        assertTrue(ms.none { it.kind == "scam" })
    }

    @Test fun everyKindAppears() {
        val kinds = SmsGenerator.generate(300, 7).groupingBy { it.kind }.eachCount()
        assertEquals(v.mix.keys, kinds.keys)
    }
}
