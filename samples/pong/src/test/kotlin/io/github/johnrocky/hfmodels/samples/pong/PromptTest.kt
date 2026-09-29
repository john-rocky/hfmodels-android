package io.github.johnrocky.hfmodels.samples.pong

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptTest {
    @Test
    fun promptIsByteIdenticalToTheReference() {
        val expected = Fixtures.bytes("prompt.txt")
        val actual = Prompt.build().toByteArray(Charsets.UTF_8)
        assertEquals(319, expected.size)
        assertArrayEquals(expected, actual)
    }
}
