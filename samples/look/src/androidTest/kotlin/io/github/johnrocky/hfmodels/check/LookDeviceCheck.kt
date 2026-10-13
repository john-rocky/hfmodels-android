package io.github.johnrocky.hfmodels.check

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.litertlm.SessionState
import io.github.johnrocky.hfmodels.samples.look.Examples
import io.github.johnrocky.hfmodels.samples.look.Looker
import io.github.johnrocky.hfmodels.samples.look.Photo
import io.github.johnrocky.hfmodels.samples.look.PhotoImport
import io.github.johnrocky.hfmodels.samples.look.Picture
import io.github.johnrocky.hfmodels.samples.look.Reply
import io.github.johnrocky.hfmodels.samples.look.Typed
import io.github.johnrocky.hfmodels.samples.look.Variants
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device check for samples/look. On the connected phone and in the app's own process it proves what the
 * screen relies on, through the app's own code (Looker, PhotoImport, Typed, Stats):
 *
 * - load: the model loads (from the app's store, or a copy pushed to its files dir is hashed and imported);
 * - photo: the Example button's photo put into MediaStore and read back through PhotoImport (what the picker's
 *   result goes through), asked "What is this a photo of?": an answer with text, streamed; its full text, the
 *   first token and the chunks are printed (whether it names the photo's subject is recorded, not required:
 *   the model's answer is not the app's to guarantee);
 * - follow-up: "What colour is it?" about the same photo, a text-only turn of the same conversation: text;
 * - stop: a third question cancelled after its first chunk; the next question opens a new conversation on a
 *   new load (one conversation per load, google-ai-edge/LiteRT-LM#3165) and answers;
 * - release: close, and close again, without an exception;
 *
 * and the failure paths, each ending in a sentence for the screen instead of a crash: missing-model (a
 * revision that is not on the phone, loaded offline: a ModelException with its code), no-photo and
 * empty-question (Typed refuses them before the model), big-photo (6000 x 4500 comes back as 768 x 576),
 * rotated-photo (a JPEG stored sideways with an EXIF rotation comes back upright) and bad-photo (bytes that are
 * not an image: an exception with a message).
 *
 *   ./gradlew :samples:look:assembleDebug :samples:look:assembleDebugAndroidTest
 *   adb install -r samples/look/build/outputs/apk/debug/look-debug.apk
 *   adb install -r samples/look/build/outputs/apk/androidTest/debug/look-debug-androidTest.apk
 *   adb shell am start -n io.github.johnrocky.hfmodels.samples.look/.MainActivity   # creates the files dir the push needs
 *   adb push MiniCPM-V-4.7-1B-VL_int8vis.litertlm /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.look/files/
 *   adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.LookDeviceCheck \
 *       -e backend cpu -e network offline -e variant vl_int8vis \
 *       io.github.johnrocky.hfmodels.samples.look.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s hfmodels-check | grep RESULT
 *
 * The photo is the app's first example (assets/examples/), a red bicycle leaning on a tree, no person, no
 * text. The check puts it into MediaStore under Pictures/hfmodels-look-check/ and deletes it at the end.
 * The last line is `RESULT ok=true ...` when every step passed.
 * Arguments: backend=gpu|cpu (default: the descriptor's default profile), network=offline|any (default: the
 * app's rule, the phone's store first), variant=vl_int8|vl_int8vis (default: the app's choice by RAM).
 */
@RunWith(AndroidJUnit4::class)
class LookDeviceCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val backend = args.getString("backend")
    private val policy = when (backend) { "cpu" -> BackendPolicy.Require(BackendKind.CPU); "gpu" -> BackendPolicy.Require(BackendKind.GPU); else -> BackendPolicy.Auto }
    private val network = when (args.getString("network")) { "offline" -> NetworkPolicy.Offline; "any" -> NetworkPolicy.Any; else -> null }
    private val totalMem = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem
    private val variant = args.getString("variant") ?: Variants.forMemory(totalMem)
    private val resolver: ContentResolver = ctx.contentResolver

    private val example = Examples.PHOTOS.first()
    private val subjectWords = listOf("bicycle", "bike")
    private val colourWords = listOf("red")

    @Test fun lookPhotoFollowUpStopRelease(): Unit = runBlocking {
        val failures = ArrayList<String>()
        val failurePaths = ArrayList<String>()
        var profile = "none"
        var components = "none"
        fun step(name: String, ok: Boolean, values: String) {
            if (!ok) failures += name
            Log.i(TAG, "RESULT step=$name ok=$ok model=${Looker.REPO} variant=$variant profile=$profile $values")
        }
        val power = ctx.getSystemService(PowerManager::class.java)
        val thermalBefore = power.currentThermalStatus
        val descriptor = ctx.assets.open(Looker.DESCRIPTOR_ASSET).bufferedReader().use { it.readText() }
        val models = HfModels(ctx)
        val looker = Looker(models, descriptor, variant, policy = policy, network = network)
        val events = ArrayList<String>()
        looker.onEvent = { e -> if (e !is LoadEvent.Downloading) { events += e.javaClass.simpleName; Log.i(TAG, "event $e") } }
        var sdk = "?"
        var runtime = "?"

        // load
        val t0 = SystemClock.elapsedRealtime()
        try {
            val m = withTimeout(LOAD_TIMEOUT_MS) { looker.load() }
            profile = m.info.profileId; sdk = m.info.sdkVersion; runtime = "${m.info.runtime} ${m.info.runtimeVersion}"
            components = m.info.components.entries.joinToString(",") { "${it.key}=${it.value.initialized}" }
            step("load", true, "backend=${backend ?: "default"} network=${looker.loadNetwork} load_ms=${looker.loadMs} downloaded=${looker.downloaded} " +
                "commit=${m.info.commit.take(8)} components=$components fallback=${m.info.fallbackHistory} total_mem=$totalMem events=$events")
        } catch (t: Throwable) {
            step("load", false, "backend=${backend ?: "default"} network=$network error=${what(t)} load_ms=${SystemClock.elapsedRealtime() - t0}")
            Log.i(TAG, "RESULT ok=false failed=$failures device=${Build.MODEL} build=${Build.DISPLAY}")
            models.closeAndJoin()
            assertTrue("load failed: ${what(t)}", false)
            return@runBlocking
        }

        var current = "photo"
        var photoUri: Uri? = null
        try {
            // photo: the example's bytes -> MediaStore -> PhotoImport (the picker's path) -> a new conversation
            val source = ctx.assets.open(example.asset).use { it.readBytes() }
            removeCheckPhotos(resolver)
            photoUri = insertPhoto(resolver, "look-check-${example.asset.substringAfterLast('/')}", source)
            val i0 = SystemClock.elapsedRealtimeNanos()
            val photo = PhotoImport.load(resolver, photoUri)
            val importMs = (SystemClock.elapsedRealtimeNanos() - i0) / 1e6
            val pic = Picture(photo.bytes)
            val q1 = "What is this a photo of?"
            val ready = Typed.check(true, q1) as Typed.Result.Ready
            val pr = withTimeout(LOAD_TIMEOUT_MS + TURN_TIMEOUT_MS) { looker.ask(pic, ready.question) }
            step("photo", pr.firstTurn && pr.text.isNotBlank(),
                "photo=${example.asset} source=${photo.sourceWidth}x${photo.sourceHeight} sent=${photo.width}x${photo.height} jpeg_bytes=${photo.bytes.size} " +
                    "jpeg_sha256=${sha256(photo.bytes)} import_ms=${ms(importMs)} question=${q(q1)} names_subject=${names(pr.text, subjectWords)} " +
                    "${timing(pr)} conversation_ms=${ms(pr.conversationMs)} reload_ms=${pr.loadMs} first_turn=${pr.firstTurn} answer=${q(pr.text)}")

            // follow-up: the same photo, a text-only turn of the same conversation
            current = "follow-up"
            val q2 = "What colour is it?"
            val fr = withTimeout(TURN_TIMEOUT_MS) { looker.ask(pic, q2) }
            step("follow-up", !fr.firstTurn && fr.loadMs == null && fr.text.isNotBlank(),
                "question=${q(q2)} names_colour=${names(fr.text, colourWords)} ${timing(fr)} first_turn=${fr.firstTurn} answer=${q(fr.text)}")

            // stop: a third question cancelled after its first chunk; the next question opens a new conversation on a new load
            current = "stop"
            val q3 = "Describe everything you can see in this photo, in detail."
            var finished: Reply? = null
            var thrown: Throwable? = null
            val firstChunk = AtomicBoolean(false)
            val s0 = SystemClock.elapsedRealtimeNanos()
            val job = launch { try { finished = looker.ask(pic, q3) { _, _ -> firstChunk.set(true) } } catch (t: Throwable) { thrown = t; throw t } }
            val streaming = runCatching { withTimeout(TURN_TIMEOUT_MS) { while (!firstChunk.get() && job.isActive) delay(2) } }.isSuccess && firstChunk.get()
            val stateAtCancel = looker.sessionState
            val cancelAfterMs = (SystemClock.elapsedRealtimeNanos() - s0) / 1e6
            job.cancelAndJoin()
            val stopped = finished == null && thrown is kotlinx.coroutines.CancellationException
            val closed = looker.sessionState == null
            val after = withTimeout(LOAD_TIMEOUT_MS + TURN_TIMEOUT_MS) { looker.ask(pic, ready.question) }
            step("stop", streaming && stateAtCancel == SessionState.GENERATING && stopped && closed && after.firstTurn && after.loadMs != null && after.text.isNotBlank(),
                "state_at_cancel=$stateAtCancel cancel_after_ms=${ms(cancelAfterMs)} outcome=${if (stopped) "cancelled" else if (finished != null) "answered_first" else what(thrown)} " +
                    "conversation_closed=$closed next_question=${q(q1)} next_first_turn=${after.firstTurn} reload_ms=${after.loadMs} " +
                    "next_conversation_ms=${ms(after.conversationMs)} next_names_subject=${names(after.text, subjectWords)} next_same_as_first=${after.text == pr.text} " +
                    "${timing(after, "next_")} next_answer=${q(after.text)}")
        } catch (t: Throwable) {
            step(current, false, "error=${what(t)}")
        } finally {
            // release: close, then close again; neither may throw (10 s cap inside the SDK)
            val r0 = SystemClock.elapsedRealtime()
            val first = runCatching { looker.close() }
            val closeMs = SystemClock.elapsedRealtime() - r0
            val second = runCatching { looker.close() }
            val client = runCatching { models.closeAndJoin(); models.closeAndJoin() }
            val errors = listOf(first, second, client).mapNotNull { it.exceptionOrNull()?.let(::what) }
            step("release", errors.isEmpty() && closeMs < 10_000, "close_ms=$closeMs second_close=${if (second.isSuccess) "no-op" else "threw"} errors=$errors")
            photoUri?.let { resolver.delete(it, null, null) }
        }

        // The failure paths: each must end in a sentence for the screen, never a crash.
        fun path(name: String, ok: Boolean, values: String) { if (ok) failurePaths += name; step(name, ok, values) }

        // missing-model: a revision that is not on the phone, loaded offline (a second client: the first is closed)
        val other = HfModels(ctx)
        try {
            val missing = Looker(other, descriptorJson = null, variant = variant, revision = MISSING_REVISION, policy = policy, network = NetworkPolicy.Offline)
            val e = runCatching { withTimeout(LOAD_TIMEOUT_MS) { missing.load() } }.exceptionOrNull()
            path("missing-model", e is ModelException, "revision=${MISSING_REVISION.take(8)} network=Offline screen=${q(e?.let { Looker.describe(it) } ?: "loaded")}")
            missing.close()
        } finally {
            other.closeAndJoin()
        }

        // no-photo, empty-question: refused before the model is involved
        fun refused(r: Typed.Result) = (r as? Typed.Result.Refused)?.message
        refused(Typed.check(false, "What is this a photo of?")).let { path("no-photo", it == Typed.NO_PHOTO, "screen=${q(it ?: "accepted")}") }
        refused(Typed.check(true, "   ")).let { path("empty-question", it == Typed.NO_QUESTION, "screen=${q(it ?: "accepted")}") }

        // big-photo: 6000 x 4500 (27 megapixels, more than the SDK's 25-megapixel image cap) comes back as 768 x 576
        run {
            var uri: Uri? = null
            var sourceBytes = 0
            var importMs = 0.0
            val result = runCatching {
                val jpeg = bigJpeg(6000, 4500)
                sourceBytes = jpeg.size
                val u = insertPhoto(resolver, "look-check-big.jpg", jpeg)
                uri = u
                val b0 = SystemClock.elapsedRealtimeNanos()
                PhotoImport.load(resolver, u).also { importMs = (SystemClock.elapsedRealtimeNanos() - b0) / 1e6 }
            }
            uri?.let { resolver.delete(it, null, null) }
            val p: Photo? = result.getOrNull()
            path("big-photo", p != null && p.width == 768 && p.height == 576 && p.sourceWidth == 6000 && p.sourceHeight == 4500,
                if (p == null) "error=${what(result.exceptionOrNull())}" else "source=${p.sourceWidth}x${p.sourceHeight} source_bytes=$sourceBytes sent=${p.width}x${p.height} import_ms=${ms(importMs)}")
        }

        // rotated-photo: a 400 x 300 JPEG whose EXIF says "rotate 90 degrees" (how many phones store an upright
        // shot) comes back 300 x 400: the model gets the photo the way the gallery shows it
        run {
            var uri: Uri? = null
            val result = runCatching {
                val f = File(ctx.cacheDir, "look-check-rotated.jpg")
                f.writeBytes(bigJpeg(400, 300))
                ExifInterface(f.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
                val bytes = f.readBytes().also { f.delete() }
                val u = insertPhoto(resolver, "look-check-rotated.jpg", bytes)
                uri = u
                PhotoImport.load(resolver, u)
            }
            uri?.let { resolver.delete(it, null, null) }
            val p: Photo? = result.getOrNull()
            path("rotated-photo", p != null && p.width == 300 && p.height == 400,
                if (p == null) "error=${what(result.exceptionOrNull())}" else "stored=400x300 exif_orientation=6 header=${p.sourceWidth}x${p.sourceHeight} sent=${p.width}x${p.height}")
        }

        // bad-photo: bytes that are not an image; the import throws, and the screen shows its message
        run {
            var uri: Uri? = null
            val e = runCatching {
                val u = insertPhoto(resolver, "look-check-bad.jpg", "not an image".toByteArray())
                uri = u
                PhotoImport.load(resolver, u)
            }.exceptionOrNull()
            uri?.let { resolver.delete(it, null, null) }
            path("bad-photo", e != null && e !is OutOfMemoryError, "screen=${q("Could not read that photo: " + (e?.let { Looker.describe(it) } ?: "it read"))}")
        }

        Log.i(TAG, "RESULT ok=${failures.isEmpty()} model=${Looker.REPO}@${Looker.REVISION.take(8)} variant=$variant profile=$profile components=$components failed=$failures " +
            "failure_paths_ok=${failurePaths.size} sdk=$sdk runtime=$runtime device=${Build.MODEL} soc=${Build.SOC_MODEL} build=${Build.DISPLAY} " +
            "thermal=$thermalBefore->${power.currentThermalStatus} vmhwm_kb=${vmHwmKb()}")
        assertTrue("failed steps: $failures (adb logcat -d -s hfmodels-check)", failures.isEmpty())
    }

    /** The answer's numbers as RESULT fields: first token, chunks with text, every chunk, chunks per second, the stream's length. */
    private fun timing(r: Reply, p: String = "") = "${p}first_token_ms=${ms(r.timing.firstTokenMs)} ${p}chunks=${r.timing.chunks} ${p}all_chunks=${r.timing.allChunks} " +
        "${p}chunks_per_s=${ms(r.timing.chunksPerSecond)} ${p}stream_ms=${ms(r.timing.streamMs)} ${p}chars=${r.timing.chars}"

    private fun names(text: String, words: List<String>) = words.any { text.contains(it, ignoreCase = true) }

    /** A JPEG of [w] x [h]: a sky, a field and a sun, so the scaled photo is not one flat colour. */
    private fun bigJpeg(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        try {
            Canvas(b).apply {
                drawColor(0xFF87CEEB.toInt())
                drawRect(0f, h * 0.6f, w.toFloat(), h.toFloat(), Paint().apply { color = 0xFF3C8D2F.toInt() })
                drawCircle(w * 0.75f, h * 0.25f, h * 0.1f, Paint().apply { color = 0xFFFFD21E.toInt(); isAntiAlias = true })
            }
            return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        } finally {
            b.recycle()
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The process's peak resident set so far (VmHWM in /proc/self/status), in kB. */
    private fun vmHwmKb(): Long? = runCatching {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }?.filter { it.isDigit() }?.toLong()
    }.getOrNull()

    private fun ms(v: Double?) = v?.let { Math.round(it * 10) / 10.0 }

    /** One RESULT field: the whole text on one line (newlines as \n), double quotes as single. */
    private fun q(s: String) = "\"" + s.replace("\n", "\\n").replace("\"", "'") + "\""

    /** An error as one RESULT field: the SDK's code and reason, or the exception's class and message. */
    private fun what(t: Throwable?) = when (t) {
        null -> "none"
        is ModelException -> "${t.code} reason=${q(t.reason)}"
        else -> "${t.javaClass.simpleName} reason=${q(t.message ?: "")}"
    }

    companion object {
        const val TAG = "hfmodels-check"
        const val LOAD_TIMEOUT_MS = 240_000L
        const val TURN_TIMEOUT_MS = 120_000L
        /** A commit the model repo does not have: nothing is cached for it, and offline nothing can be fetched. */
        const val MISSING_REVISION = "0000000000000000000000000000000000000001"
        const val RELATIVE_PATH = "Pictures/hfmodels-look-check/"

        /** Puts [bytes] into MediaStore as a JPEG under [RELATIVE_PATH], as a camera or a download would. */
        fun insertPhoto(resolver: ContentResolver, name: String, bytes: ByteArray): Uri {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)) { "MediaStore insert returned null" }
            requireNotNull(resolver.openOutputStream(uri)) { "no output stream for $uri" }.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        }

        /** Deletes what this check put into MediaStore (the app's own rows under [RELATIVE_PATH]); returns how many. */
        fun removeCheckPhotos(resolver: ContentResolver): Int {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val ids = ArrayList<Long>()
            resolver.query(collection, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.RELATIVE_PATH}=?", arrayOf(RELATIVE_PATH), null)?.use { c ->
                while (c.moveToNext()) ids += c.getLong(0)
            }
            return ids.sumOf { resolver.delete(Uri.withAppendedPath(collection, it.toString()), null, null) }
        }
    }
}
