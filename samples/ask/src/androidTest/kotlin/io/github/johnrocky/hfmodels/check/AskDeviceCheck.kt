package io.github.johnrocky.hfmodels.check

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
import io.github.johnrocky.hfmodels.samples.ask.Answer
import io.github.johnrocky.hfmodels.samples.ask.Asker
import io.github.johnrocky.hfmodels.samples.ask.ChartFile
import io.github.johnrocky.hfmodels.samples.ask.ChartFrame
import io.github.johnrocky.hfmodels.samples.ask.Photo
import io.github.johnrocky.hfmodels.samples.ask.PhotoImport
import io.github.johnrocky.hfmodels.samples.ask.Picture
import io.github.johnrocky.hfmodels.samples.ask.Png
import io.github.johnrocky.hfmodels.samples.ask.Questions
import io.github.johnrocky.hfmodels.samples.ask.Reply
import io.github.johnrocky.hfmodels.samples.ask.Typed
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device check for samples/ask. On the connected phone and in the app's own process it proves what the
 * screen relies on, through the app's own code (Asker, PhotoImport, Typed):
 *
 * - load: the model loads (from the app's store, or a copy pushed to its files dir is hashed and imported);
 * - chart: chart_00 and its first question, the Demo's first turn; the letter must match the chart's data;
 * - photo: a photo put into MediaStore and read back through PhotoImport (what the picker's result goes
 *   through), asked "What is this a photo of?" with three options: a letter A to C (whether it is the
 *   photo's subject is recorded, not required: the model's answer is not the app's to guarantee);
 * - follow-up: a second question about the same photo, a text-only turn: a letter;
 * - stop: a third question cancelled before its letter; the next question opens a new conversation on a
 *   new load (one conversation per load, google-ai-edge/LiteRT-LM#3165) and answers;
 * - release: close, and close again, without an exception;
 *
 * and the failure paths, each ending in a sentence for the screen instead of a crash: missing-model (a
 * revision that is not on the phone, loaded offline: a ModelException with its code), no-photo,
 * empty-question, one-option (Typed refuses them before the model), big-photo (6000 x 4500 comes back as
 * 768 x 576), rotated-photo (a JPEG stored sideways with an EXIF rotation comes back upright) and bad-photo
 * (bytes that are not an image: an exception with a message).
 *
 *   ./gradlew :samples:ask:assembleDebug :samples:ask:assembleDebugAndroidTest
 *   adb install -r samples/ask/build/outputs/apk/debug/ask-debug.apk
 *   adb install -r samples/ask/build/outputs/apk/androidTest/debug/ask-debug-androidTest.apk
 *   adb shell am start -n io.github.johnrocky.hfmodels.samples.ask/.MainActivity   # creates the files dir the push needs
 *   adb push decider-2b-vision_int8.litertlm a01_red_bicycle.jpg \
 *       /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.ask/files/   # the model: optional, else the load downloads 3.2 GB
 *   adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.AskDeviceCheck -e backend gpu -e network offline \
 *       io.github.johnrocky.hfmodels.samples.ask.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s hfmodels-check | grep RESULT
 *
 * The photo is a01_red_bicycle.jpg in the app's external files dir (CC0, Bernard Spragg on Flickr, from the
 * probes/eg2demo album: a red bicycle leaning on a tree, no person, no text); the questions are about it. The
 * check puts it into MediaStore under Pictures/hfmodels-ask-check/ and deletes it at the end; keep_photo=true
 * leaves it there for the photo picker, and the class AskCheckPhotos below deletes it later.
 * The last line is `RESULT ok=true ...` when every step passed.
 * Arguments: backend=gpu|cpu (default: the descriptor's default profile), network=offline|any (default any), keep_photo.
 */
@RunWith(AndroidJUnit4::class)
class AskDeviceCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val backend = args.getString("backend")
    private val policy = when (backend) { "cpu" -> BackendPolicy.Require(BackendKind.CPU); "gpu" -> BackendPolicy.Require(BackendKind.GPU); else -> BackendPolicy.Auto }
    private val network = if (args.getString("network") == "offline") NetworkPolicy.Offline else NetworkPolicy.Any
    private val keepPhoto = args.getString("keep_photo") == "true"
    private val resolver: ContentResolver = ctx.contentResolver

    private val photoName = "a01_red_bicycle.jpg"
    private val photoOptions = listOf("a car", "a boat", "a bicycle")
    private val photoExpected = 2

    @Test fun askPhotoChartStopRelease(): Unit = runBlocking {
        val failures = ArrayList<String>()
        val failurePaths = ArrayList<String>()
        var profile = "none"
        fun step(name: String, ok: Boolean, values: String) {
            if (!ok) failures += name
            Log.i(TAG, "RESULT step=$name ok=$ok model=${Asker.REPO} profile=$profile $values")
        }
        val power = ctx.getSystemService(PowerManager::class.java)
        val thermalBefore = power.currentThermalStatus
        val descriptor = ctx.assets.open(Asker.DESCRIPTOR_ASSET).bufferedReader().use { it.readText() }
        val models = HfModels(ctx)
        val asker = Asker(models, descriptor, policy = policy, network = network)
        val events = ArrayList<String>()
        asker.onEvent = { e -> if (e !is LoadEvent.Downloading) { events += e.javaClass.simpleName; Log.i(TAG, "event $e") } }
        var sdk = "?"
        var runtime = "?"

        // load
        val t0 = SystemClock.elapsedRealtime()
        try {
            val m = withTimeout(LOAD_TIMEOUT_MS) { asker.load() }
            profile = m.info.profileId; sdk = m.info.sdkVersion; runtime = "${m.info.runtime} ${m.info.runtimeVersion}"
            step("load", true, "backend=${backend ?: "default"} network=$network load_ms=${asker.loadMs} downloaded=${asker.downloaded} " +
                "commit=${m.info.commit.take(8)} variant=${m.info.variantId} fallback=${m.info.fallbackHistory} events=$events")
        } catch (t: Throwable) {
            step("load", false, "backend=${backend ?: "default"} network=$network error=${what(t)} load_ms=${SystemClock.elapsedRealtime() - t0}")
            Log.i(TAG, "RESULT ok=false failed=$failures device=${Build.MODEL} build=${Build.DISPLAY}")
            models.closeAndJoin()
            assertTrue("load failed: ${what(t)}", false)
            return@runBlocking
        }

        var current = "chart"
        var photoUri: Uri? = null
        try {
            // chart: the Demo's first turn on chart_00 (the PNG the app draws, its first question, its context line)
            val spec = ChartFile.parse(ctx.assets.open("charts.json").bufferedReader().use { it.readText() })[0].spec
            val cq = Questions.of(spec)[0]
            val chart = Picture(Png.encode(ChartFrame.render(spec), ChartFrame.SIDE, ChartFrame.SIDE), spec.context)
            val cr = withTimeout(TURN_TIMEOUT_MS) { asker.ask(chart, cq.text, cq.options) }
            step("chart", cr.firstTurn && cr.choice == cq.expected,
                "chart=${spec.id} question=${q(cq.text)} letter=${Answer.letter(cr.choice)} expected=${Answer.letter(cq.expected)} raw=${q(cr.text)} " +
                    "answer_ms=${ms(cr.answerMs)} stream_ms=${ms(cr.streamMs)} conversation_ms=${ms(cr.conversationMs)} first_turn=${cr.firstTurn}")

            // photo: MediaStore -> PhotoImport (the picker's path) -> a new conversation (a new load: the chart held the last one)
            current = "photo"
            val file = File(ctx.getExternalFilesDir(null), photoName)
            require(file.isFile) { "push $photoName to ${file.parent} first" }
            removeCheckPhotos(resolver)
            photoUri = insertPhoto(resolver, "ask-check-$photoName", file.readBytes())
            val i0 = SystemClock.elapsedRealtimeNanos()
            val photo = PhotoImport.load(resolver, photoUri)
            val importMs = (SystemClock.elapsedRealtimeNanos() - i0) / 1e6
            val pic = Picture(photo.bytes)
            val q1 = "What is this a photo of?"
            val ready = Typed.check(true, q1, photoOptions) as Typed.Result.Ready
            val pr = withTimeout(LOAD_TIMEOUT_MS + TURN_TIMEOUT_MS) { asker.ask(pic, ready.question, ready.options) }
            step("photo", pr.firstTurn && pr.choice in ready.options.indices,
                "photo=$photoName source=${photo.sourceWidth}x${photo.sourceHeight} sent=${photo.width}x${photo.height} jpeg_bytes=${photo.bytes.size} import_ms=${ms(importMs)} " +
                    "question=${q(q1)} options=${q(ready.options.joinToString("|"))} letter=${Answer.letter(pr.choice)} subject=${Answer.letter(photoExpected)} " +
                    "matches_subject=${pr.choice == photoExpected} raw=${q(pr.text)} answer_ms=${ms(pr.answerMs)} stream_ms=${ms(pr.streamMs)} " +
                    "conversation_ms=${ms(pr.conversationMs)} reload_ms=${pr.loadMs} first_turn=${pr.firstTurn}")

            // follow-up: the same photo, a text-only turn of the same conversation
            current = "follow-up"
            val q2 = "What colour is the bicycle?"
            val o2 = listOf("blue", "red", "green")
            val fr = withTimeout(TURN_TIMEOUT_MS) { asker.ask(pic, q2, o2) }
            step("follow-up", !fr.firstTurn && fr.loadMs == null && fr.choice in o2.indices,
                "question=${q(q2)} options=${q(o2.joinToString("|"))} letter=${Answer.letter(fr.choice)} photo_shows=B matches_photo=${fr.choice == 1} raw=${q(fr.text)} answer_ms=${ms(fr.answerMs)} " +
                    "stream_ms=${ms(fr.streamMs)} first_turn=${fr.firstTurn}")

            // stop: a third question cancelled before its letter; the next question opens a new conversation on a new load
            current = "stop"
            val q3 = "Is it day or night in the photo?"
            val o3 = listOf("day", "night")
            var finished: Reply? = null
            var thrown: Throwable? = null
            val s0 = SystemClock.elapsedRealtimeNanos()
            val job = launch { try { finished = asker.ask(pic, q3, o3) } catch (t: Throwable) { thrown = t; throw t } }
            val generating = runCatching { withTimeout(TURN_TIMEOUT_MS) { while (asker.sessionState != SessionState.GENERATING && job.isActive) delay(2) } }.isSuccess
            val stateAtCancel = asker.sessionState
            val cancelAfterMs = (SystemClock.elapsedRealtimeNanos() - s0) / 1e6
            job.cancelAndJoin()
            val stopped = finished == null && thrown is kotlinx.coroutines.CancellationException
            val closed = asker.sessionState == null
            val after = withTimeout(LOAD_TIMEOUT_MS + TURN_TIMEOUT_MS) { asker.ask(pic, ready.question, ready.options) }
            step("stop", generating && stopped && closed && after.firstTurn && after.loadMs != null && after.choice in ready.options.indices,
                "state_at_cancel=$stateAtCancel cancel_after_ms=${ms(cancelAfterMs)} outcome=${if (stopped) "cancelled" else if (finished != null) "answered_first" else what(thrown)} " +
                    "conversation_closed=$closed next_question=${q(q1)} next_letter=${Answer.letter(after.choice)} next_matches_subject=${after.choice == photoExpected} " +
                    "next_first_turn=${after.firstTurn} reload_ms=${after.loadMs} next_conversation_ms=${ms(after.conversationMs)} next_answer_ms=${ms(after.answerMs)}")
        } catch (t: Throwable) {
            step(current, false, "error=${what(t)}")
        } finally {
            // release: close, then close again; neither may throw (10 s cap inside the SDK)
            val r0 = SystemClock.elapsedRealtime()
            val first = runCatching { asker.close() }
            val closeMs = SystemClock.elapsedRealtime() - r0
            val second = runCatching { asker.close() }
            val client = runCatching { models.closeAndJoin(); models.closeAndJoin() }
            val errors = listOf(first, second, client).mapNotNull { it.exceptionOrNull()?.let(::what) }
            step("release", errors.isEmpty() && closeMs < 10_000, "close_ms=$closeMs second_close=${if (second.isSuccess) "no-op" else "threw"} errors=$errors")
            photoUri?.let { uri -> if (keepPhoto) Log.i(TAG, "kept $uri in Pictures/hfmodels-ask-check/") else resolver.delete(uri, null, null) }
        }

        // The failure paths: each must end in a sentence for the screen, never a crash.
        fun path(name: String, ok: Boolean, values: String) { if (ok) failurePaths += name; step(name, ok, values) }

        // missing-model: a revision that is not on the phone, loaded offline (a second client: the first is closed)
        val other = HfModels(ctx)
        try {
            val missing = Asker(other, descriptorJson = null, revision = MISSING_REVISION, policy = policy, network = NetworkPolicy.Offline)
            val e = runCatching { withTimeout(LOAD_TIMEOUT_MS) { missing.load() } }.exceptionOrNull()
            path("missing-model", e is ModelException, "revision=${MISSING_REVISION.take(8)} network=Offline screen=${q(e?.let { Asker.describe(it) } ?: "loaded")}")
            missing.close()
        } finally {
            other.closeAndJoin()
        }

        // no-photo, empty-question, one-option: refused before the model is involved
        val o1 = photoOptions
        fun refused(r: Typed.Result) = (r as? Typed.Result.Refused)?.message
        refused(Typed.check(false, "What is this a photo of?", o1)).let { path("no-photo", it == Typed.NO_PHOTO, "screen=${q(it ?: "accepted")}") }
        refused(Typed.check(true, "   ", o1)).let { path("empty-question", it == Typed.NO_QUESTION, "screen=${q(it ?: "accepted")}") }
        refused(Typed.check(true, "What is this a photo of?", listOf("a bicycle", "", "", "", ""))).let { path("one-option", it == Typed.TOO_FEW, "screen=${q(it ?: "accepted")}") }

        // big-photo: 6000 x 4500 (27 megapixels, more than the SDK's 25-megapixel image cap) comes back as 768 x 576
        run {
            var uri: Uri? = null
            var sourceBytes = 0
            var importMs = 0.0
            val result = runCatching {
                val jpeg = bigJpeg(6000, 4500)
                sourceBytes = jpeg.size
                val u = insertPhoto(resolver, "ask-check-big.jpg", jpeg)
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
                val f = File(ctx.cacheDir, "ask-check-rotated.jpg")
                f.writeBytes(bigJpeg(400, 300))
                ExifInterface(f.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
                val bytes = f.readBytes().also { f.delete() }
                val u = insertPhoto(resolver, "ask-check-rotated.jpg", bytes)
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
                val u = insertPhoto(resolver, "ask-check-bad.jpg", "not an image".toByteArray())
                uri = u
                PhotoImport.load(resolver, u)
            }.exceptionOrNull()
            uri?.let { resolver.delete(it, null, null) }
            path("bad-photo", e != null && e !is OutOfMemoryError, "screen=${q("Could not read that photo: " + (e?.let { Asker.describe(it) } ?: "it read"))}")
        }

        Log.i(TAG, "RESULT ok=${failures.isEmpty()} model=${Asker.REPO}@${Asker.REVISION.take(8)} variant=${Asker.VARIANT} profile=$profile failed=$failures " +
            "failure_paths_ok=${failurePaths.size} sdk=$sdk runtime=$runtime device=${Build.MODEL} soc=${Build.SOC_MODEL} build=${Build.DISPLAY} " +
            "thermal=$thermalBefore->${power.currentThermalStatus}")
        assertTrue("failed steps: $failures (adb logcat -d -s hfmodels-check)", failures.isEmpty())
    }

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

    private fun ms(v: Double?) = v?.let { Math.round(it * 10) / 10.0 }

    private fun q(s: String) = "\"" + s.take(160).replace("\n", " ").replace("\"", "'") + "\""

    /** An error as one RESULT field: the SDK's code and reason, or the exception's class and message. */
    private fun what(t: Throwable?) = when (t) {
        null -> "none"
        is ModelException -> "${t.code} reason=${q(t.reason)}"
        else -> "${t.javaClass.simpleName} reason=${q(t.message ?: "")}"
    }

    companion object {
        const val TAG = "hfmodels-check"
        const val LOAD_TIMEOUT_MS = 240_000L
        const val TURN_TIMEOUT_MS = 60_000L
        /** A commit the model repo does not have: nothing is cached for it, and offline nothing can be fetched. */
        const val MISSING_REVISION = "0000000000000000000000000000000000000001"
        const val RELATIVE_PATH = "Pictures/hfmodels-ask-check/"

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

/**
 * Deletes the photo AskDeviceCheck left in MediaStore with keep_photo=true (after the picker was shown):
 *
 *   adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.AskCheckPhotos \
 *       io.github.johnrocky.hfmodels.samples.ask.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class AskCheckPhotos {
    @Test fun remove() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val removed = AskDeviceCheck.removeCheckPhotos(resolver)
        Log.i(AskDeviceCheck.TAG, "RESULT step=remove-photos ok=true removed=$removed path=${AskDeviceCheck.RELATIVE_PATH}")
    }
}
