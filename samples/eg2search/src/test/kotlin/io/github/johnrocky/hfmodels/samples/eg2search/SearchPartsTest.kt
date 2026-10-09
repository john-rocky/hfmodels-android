package io.github.johnrocky.hfmodels.samples.eg2search

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The parts of the sample that need no phone: the input gate, the album's file names, ranking, the WAV helpers. */
class SearchPartsTest {
    @Test fun gateRefusesShortThenQuiet() {
        assertEquals(InputGate.Reason.SHORT, InputGate.check(0.3, 0.1))
        assertEquals(InputGate.Reason.SHORT, InputGate.check(0.05, 0.0))
        assertEquals(InputGate.Reason.QUIET, InputGate.check(1.5, 0.0034))
        assertNull(InputGate.check(1.5, 0.02))
        // The floor and the length themselves pass.
        assertNull(InputGate.check(InputGate.MIN_SECONDS, InputGate.MIN_RMS.toDouble()))
        assertEquals(InputGate.Reason.QUIET, InputGate.check(1.5, 0.004, minRms = 0.005f))
        assertNull(InputGate.check(1.5, 0.004, minRms = 0.001f))
    }

    @Test fun copiesAreNamedFromThePickedFile() {
        assertEquals("u01_img-20261009-123456.jpg", Album.fileName(1, "IMG_20261009_123456.HEIC"))
        assertEquals("u03_photo.jpg", Album.fileName(3, null))
        assertEquals("u12_photo.jpg", Album.fileName(12, "写真.jpg"))
        assertEquals("u100_beach-day.jpg", Album.fileName(100, "Beach  Day!.png"))
        assertEquals(40, Album.fileName(1, "a".repeat(80) + ".jpg").removePrefix("u01_").removeSuffix(".jpg").length)
    }

    @Test fun removeTakesOnlyTheCopies() {
        val dir = Files.createTempDirectory("album").toFile()
        try {
            for (n in listOf("a01_red_bicycle.jpg", "a02_lighthouse.jpeg", "u01_cat.jpg", "u07_dog.jpg", "notes.txt", "u02_x.jpg.tmp")) {
                File(dir, n).writeText("x")
            }
            assertEquals(listOf("a01_red_bicycle.jpg", "a02_lighthouse.jpeg", "u01_cat.jpg", "u07_dog.jpg"), Album.photos(dir).map { it.name })
            assertEquals(8, Album.nextNumber(dir))
            assertEquals(2, Album.removeUserPhotos(dir))
            assertEquals(listOf("a01_red_bicycle.jpg", "a02_lighthouse.jpeg"), Album.photos(dir).map { it.name })
            assertEquals(1, Album.nextNumber(dir))
            assertEquals(setOf("a01_red_bicycle.jpg", "a02_lighthouse.jpeg", "notes.txt", "u02_x.jpg.tmp"), dir.list()!!.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun idIsThePartBeforeTheFirstUnderscore() {
        assertEquals("a01", idOf(File("a01_red_bicycle.jpg")))
        assertEquals("u07", idOf(File("u07_my_dog.jpg")))
        assertEquals("photo", idOf(File("photo.jpg")))
    }

    @Test fun rankIsByDotProduct() {
        fun photo(id: String, vararg v: Float) = Photo(id, File("$id.jpg"), "", v)
        val album = listOf(photo("a", 1f, 0f), photo("b", 0.6f, 0.8f), photo("c", 0f, 1f))
        val ranked = rank(floatArrayOf(0f, 1f), album)
        assertEquals(listOf("c", "b", "a"), ranked.map { it.photo.id })
        assertEquals(0.8f, ranked[1].cos, 1e-6f)
        assertEquals(emptyList<Match>(), rank(floatArrayOf(1f), emptyList()))
    }

    @Test fun backendLineNamesEveryStage() {
        assertEquals("audio encoder on CPU · backbone and photos on GPU", Backends().line())
        assertEquals("all on GPU", Backends("gpu", "gpu", "gpu").line())
        assertEquals("audio encoder and backbone on CPU · photos on GPU", Backends("cpu", "gpu", "cpu").line())
        assertEquals("audio encoder on CPU · backbone and photos on NPU", Backends("npu").line())
    }

    @Test fun wavRoundTripAndRms() {
        val pcm = ByteArray(3200) { k -> if (k % 2 == 0) 0xff.toByte() else 0x7f.toByte() } // 1600 samples of 32767
        val f = Files.createTempFile("clip", ".wav").toFile()
        try {
            f.writeBytes(Wav.mono16(pcm, 16000))
            val back = Wav.read(f)
            assertEquals(16000, back.sampleRate)
            assertEquals(1, back.channels)
            assertEquals(16, back.bits)
            assertArrayEquals(pcm, back.data)
            assertEquals(0.1, back.seconds, 1e-9)
            assertEquals(32767.0 / 32768.0, Wav.rms(pcm, 0, pcm.size), 1e-9)
            assertEquals(0.0, Wav.rms(ByteArray(100), 0, 100), 0.0)
        } finally {
            f.delete()
        }
    }
}
