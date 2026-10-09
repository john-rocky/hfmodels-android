package io.github.johnrocky.hfmodels.check

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.johnrocky.hfmodels.samples.eg2search.Album
import io.github.johnrocky.hfmodels.samples.eg2search.Backends
import io.github.johnrocky.hfmodels.samples.eg2search.BuildConfig
import io.github.johnrocky.hfmodels.samples.eg2search.InputGate
import io.github.johnrocky.hfmodels.samples.eg2search.Mic
import io.github.johnrocky.hfmodels.samples.eg2search.Photo
import io.github.johnrocky.hfmodels.samples.eg2search.PhotoSearch
import io.github.johnrocky.hfmodels.samples.eg2search.Wav
import io.github.johnrocky.hfmodels.samples.eg2search.idOf
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Device check for samples/eg2search. On the connected phone and in the app's own process it runs the code the screen
 * runs (PhotoSearch, Album, Mic, InputGate) and prints one `RESULT step=<name> ok=<bool> ...` line per step under tag
 * hfmodels-check, then `RESULT ok=<all> ...`:
 *   load            the bundle loads: path, bytes, sha256, the backend of each stage, init and warm-up ms
 *   index           every album photo is embedded (reindex): count, total and per-photo ms
 *   import          one album photo, enlarged 2x and saved into MediaStore as this app's image, goes through
 *                   Album.importUris, the photo picker's path: one u<NN>_*.jpg at most 1024 px long, and the next index
 *                   embeds that photo only
 *   import_none     an empty pick changes nothing
 *   query_<clip>    each clip in files/queries/ that has a gold photo in files/queries/queries.json: top-1 = gold
 *   no_match        1.5 s of noise at RMS 0.0034 (the louder of two silent recordings on a Galaxy S26) is refused as
 *                   quiet, and 0.3 s cut from the middle of the first clip as short, before the model
 *   stop            an index of the whole album, cancelled from this thread after its first photo, returns within 5 s,
 *                   keeps every photo searchable, and the first clip still finds its gold
 *   release         close twice: the second call does nothing, and a search after close fails with IllegalStateException
 *   reload          a second load in the same process indexes from the cache (0 embedded) and finds the first clip's gold
 *   missing_bundle  a bundle that is not there comes back as BUNDLE_MISSING, naming the file and the push directory
 *   permission      with RECORD_AUDIO revoked, the mic path records nothing and returns the screen's sentence
 * At the end it deletes the photo and the MediaStore row it added.
 *
 *   (first: start the app once, push the bundle, the album and three clips with queries.json; README.md)
 *   ./gradlew :samples:eg2search:assembleDebug :samples:eg2search:assembleDebugAndroidTest
 *   adb install -r samples/eg2search/build/outputs/apk/debug/eg2search-debug.apk
 *   adb install -r samples/eg2search/build/outputs/apk/androidTest/debug/eg2search-debug-androidTest.apk
 *   adb shell pm revoke io.github.johnrocky.hfmodels.samples.eg2search android.permission.RECORD_AUDIO   # for the permission step
 *   adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.Eg2DeviceCheck \
 *       io.github.johnrocky.hfmodels.samples.eg2search.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s hfmodels-check | grep RESULT
 *
 * `connectedDebugAndroidTest` runs it as well, but uninstalls the app afterwards, and its files with it.
 * Arguments: backend=gpu|cpu|npu (gpu), vision_backend (= backend), audio_backend (cpu), bundle (the default file name),
 * queries=<clip names, comma> (default: every clip with a gold), import=<album file> (default: the last pushed album
 * photo that is no clip's gold).
 */
@RunWith(AndroidJUnit4::class)
class Eg2DeviceCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test fun searchMyPhotos() {
        val failures = ArrayList<String>()
        val failurePaths = ArrayList<String>()
        var steps = 0
        fun step(name: String, ok: Boolean, values: String, failurePath: Boolean = false) {
            steps++
            if (!ok) failures += name else if (failurePath) failurePaths += name
            Log.i(TAG, "RESULT step=$name ok=$ok $values")
        }
        val files = requireNotNull(ctx.getExternalFilesDir(null)) { "no external files dir" }
        val albumDir = Album.dir(ctx)
        val backbone = args.getString("backend") ?: "gpu"
        val backends = Backends(backbone, args.getString("vision_backend") ?: backbone, args.getString("audio_backend") ?: "cpu")
        val bundle = File(files, args.getString("bundle") ?: PhotoSearch.DEFAULT_BUNDLE)
        val cache = File(ctx.cacheDir, "eg2")
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val indexFile = PhotoSearch.indexFile(files, bundle.name, backends.vision)
        val power = ctx.getSystemService(PowerManager::class.java)
        val thermalBefore = power.currentThermalStatus
        val clips = clips(files)
        var imported: File? = null
        var mediaUri: Uri? = null
        var search: PhotoSearch? = null
        try {
            // load
            val t0 = SystemClock.elapsedRealtime()
            when (val o = PhotoSearch.open(bundle, cache, backends, nativeDir)) {
                is PhotoSearch.Opened.Ready -> {
                    val loadMs = SystemClock.elapsedRealtime() - t0
                    search = o.search
                    val sha = runCatching { PhotoSearch.sha256(bundle).take(12) }.getOrDefault("?")
                    step("load", true, "bundle=${bundle.path} bytes=${bundle.length()} sha256_12=$sha backends=${q(backends.line())} " +
                        "init_ms=${f1(o.search.initMs)} warmup_ms=${o.search.warmupMs?.let { f1(it) }} load_ms=$loadMs")
                }
                is PhotoSearch.Opened.Failed -> step("load", false, "bundle=${bundle.path} code=${o.code} text=${q(o.text)} error=${o.error}")
            }
            val s = search
            if (s != null) {
                var album: List<Photo> = emptyList()

                // index
                try {
                    val idx = s.index(albumDir, indexFile, reindex = true)
                    album = idx.photos
                    step("index", idx.files > 0 && idx.photos.size == idx.files && idx.failed.isEmpty() && !idx.cancelled,
                        "photos=${idx.photos.size} files=${idx.files} embedded=${idx.embedded} reused=${idx.reused} failed=${idx.failed.size} " +
                            "total_ms=${f1(idx.totalMs)} per_image_ms_median=${idx.perImageMedian?.let { f1(it) }} " +
                            "per_image_ms_min=${idx.perImageMs.minOrNull()?.let { f1(it) }} per_image_ms_max=${idx.perImageMs.maxOrNull()?.let { f1(it) }} " +
                            "cache_error=${idx.cacheError}")
                } catch (t: Throwable) {
                    step("index", false, "error=${what(t)}")
                }

                // import: the photo picker's path, with a content:// image this app put into MediaStore
                try {
                    val golds = clips.map { it.second }.toSet()
                    val source = args.getString("import")?.let { File(albumDir, it) }
                        ?: Album.photos(albumDir).lastOrNull { !Album.isUserPhoto(it) && idOf(it) !in golds }
                    requireNotNull(source) { "no album photo to import" }
                    val (uri, insertedPx, insertedLong) = insertEnlarged(source)
                    mediaUri = uri
                    val before = Album.photos(albumDir).size
                    val r = Album.importUris(ctx, listOf(uri))
                    imported = r.files.firstOrNull()
                    val after = Album.photos(albumDir).size
                    val px = imported?.let { bounds(it) }
                    val idx = s.index(albumDir, indexFile)
                    album = idx.photos
                    val longSide = px?.let { maxOf(it.first, it.second) }
                    val ok = r.files.size == 1 && r.failed.isEmpty() && after == before + 1 &&
                        imported?.let { Album.isUserPhoto(it) } == true && longSide == minOf(Album.MAX_SIDE, insertedLong) &&
                        idx.embedded == 1 && idx.photos.size == before + 1
                    step("import", ok, "source=${source.name} uri=$uri inserted_px=$insertedPx imported=${imported?.name} " +
                        "px=${px?.let { "${it.first}x${it.second}" }} bytes=${imported?.length()} album=$before->$after " +
                        "embedded=${idx.embedded} index_ms=${f1(idx.totalMs)} import_ms=${f1(r.ms)} failed=${r.failed.size} first_failure=${q(r.failed.firstOrNull() ?: "")}")
                } catch (t: Throwable) {
                    step("import", false, "error=${what(t)}")
                }

                // import_none: the picker came back empty
                try {
                    val before = Album.photos(albumDir).size
                    val r = Album.importUris(ctx, emptyList())
                    val after = Album.photos(albumDir).size
                    step("import_none", r.files.isEmpty() && r.failed.isEmpty() && after == before,
                        "imported=${r.files.size} failed=${r.failed.size} album=$before->$after", failurePath = true)
                } catch (t: Throwable) {
                    step("import_none", false, "error=${what(t)}", failurePath = true)
                }

                // query_<clip>
                if (clips.isEmpty()) step("query", false, "no clip with a gold photo in ${files.path}/queries/ (push the clips and queries.json)")
                for ((wav, gold) in clips) {
                    val name = "query_" + wav.name.removeSuffix(".wav")
                    try {
                        val pcm = Wav.read(wav)
                        when (val a = s.searchAudio(pcm.data, pcm.sampleRate, album)) {
                            is PhotoSearch.Answer.Found -> {
                                val top = a.matches.first()
                                step(name, top.photo.id == gold, "clip=${wav.name} gold=$gold top1=${top.photo.id} top1_cos=${f4(top.cos.toDouble())} " +
                                    "top3=${a.matches.take(3).joinToString(",") { "${it.photo.id}:${f4(it.cos.toDouble())}" }} " +
                                    "embed_ms=${f1(a.embedMs)} rank_ms=${f2(a.rankMs)} seconds=${f2(a.seconds)} rms=${f5(a.rms)} album=${album.size}")
                            }
                            is PhotoSearch.Answer.Refused -> step(name, false, "clip=${wav.name} gold=$gold refused=${a.reason.word} rms=${f5(a.rms)} seconds=${f2(a.seconds)}")
                        }
                    } catch (t: Throwable) {
                        step(name, false, "clip=${wav.name} error=${what(t)}")
                    }
                }

                // no_match: silence and a too-short press never reach the model
                try {
                    val quiet = noise(1.5, 0.0034)
                    val a = s.searchAudio(quiet, Mic.RATE, album)
                    val first = clips.firstOrNull()?.let { Wav.read(it.first) }
                    val b = first?.let { s.searchAudio(middle(it, 0.3), it.sampleRate, album) }
                    val ok = a is PhotoSearch.Answer.Refused && a.reason == InputGate.Reason.QUIET &&
                        b is PhotoSearch.Answer.Refused && b.reason == InputGate.Reason.SHORT
                    step("no_match", ok, "quiet=${desc(a)} short=${desc(b)} min_rms=${InputGate.MIN_RMS} min_s=${InputGate.MIN_SECONDS}", failurePath = true)
                } catch (t: Throwable) {
                    step("no_match", false, "error=${what(t)}", failurePath = true)
                }

                // stop: cancel a running index
                try {
                    val cancel = AtomicBoolean(false)
                    val done = AtomicInteger(0)
                    var res: PhotoSearch.Index? = null
                    var err: Throwable? = null
                    val th = Thread {
                        try {
                            res = s.index(albumDir, indexFile, reindex = true, cancel = { cancel.get() }) { d, _ -> done.set(d) }
                        } catch (t: Throwable) {
                            err = t
                        }
                    }
                    val w0 = SystemClock.elapsedRealtime()
                    th.start()
                    while (done.get() < 1 && th.isAlive && SystemClock.elapsedRealtime() - w0 < 60_000) Thread.sleep(2)
                    cancel.set(true)
                    val cancelAt = SystemClock.elapsedRealtime()
                    th.join(60_000)
                    val stopMs = SystemClock.elapsedRealtime() - cancelAt
                    val r = res
                    if (r != null) album = r.photos
                    val after = clips.firstOrNull()?.let { (wav, gold) ->
                        val p = Wav.read(wav)
                        (s.searchAudio(p.data, p.sampleRate, album) as? PhotoSearch.Answer.Found)?.matches?.first()?.photo?.id to gold
                    }
                    val ok = err == null && !th.isAlive && r != null && r.cancelled && r.embedded < r.toEmbed &&
                        r.photos.size == r.files && stopMs < 5_000 && after != null && after.first == after.second
                    step("stop", ok, "embedded_before_stop=${r?.embedded} to_embed=${r?.toEmbed} photos=${r?.photos?.size} files=${r?.files} " +
                        "stop_ms=$stopMs query_after=${after?.first} gold=${after?.second} error=${err?.let { what(it) }}")
                } catch (t: Throwable) {
                    step("stop", false, "error=${what(t)}")
                }

                // release: close twice
                try {
                    val t1 = SystemClock.elapsedRealtime()
                    val first = runCatching { s.close() }
                    val closeMs = SystemClock.elapsedRealtime() - t1
                    val second = runCatching { s.close() }
                    val afterClose = runCatching { s.searchAudio(ByteArray(Mic.RATE * 2), Mic.RATE, album) }.exceptionOrNull()
                    step("release", first.isSuccess && second.isSuccess && s.isClosed && afterClose is IllegalStateException,
                        "close_ms=$closeMs first=${first.exceptionOrNull()?.let { what(it) } ?: "ok"} second=${second.exceptionOrNull()?.let { what(it) } ?: "no-op"} " +
                            "search_after_close=${afterClose?.javaClass?.simpleName}", failurePath = true)
                } catch (t: Throwable) {
                    step("release", false, "error=${what(t)}", failurePath = true)
                }

                // reload: load again in the same process
                try {
                    val t2 = SystemClock.elapsedRealtime()
                    when (val o = PhotoSearch.open(bundle, cache, backends, nativeDir)) {
                        is PhotoSearch.Opened.Ready -> {
                            val loadMs = SystemClock.elapsedRealtime() - t2
                            val s2 = o.search
                            try {
                                val idx = s2.index(albumDir, indexFile)
                                val first = clips.firstOrNull()
                                val a = first?.let { (wav, _) -> Wav.read(wav).let { s2.searchAudio(it.data, it.sampleRate, idx.photos) } }
                                val top = (a as? PhotoSearch.Answer.Found)?.matches?.first()
                                step("reload", idx.embedded == 0 && idx.photos.size == idx.files && top != null && top.photo.id == first?.second,
                                    "init_ms=${f1(s2.initMs)} warmup_ms=${s2.warmupMs?.let { f1(it) }} load_ms=$loadMs photos=${idx.photos.size} " +
                                        "embedded=${idx.embedded} index_ms=${f1(idx.totalMs)} top1=${top?.photo?.id} gold=${first?.second} " +
                                        "embed_ms=${(a as? PhotoSearch.Answer.Found)?.embedMs?.let { f1(it) }}", failurePath = true)
                            } finally {
                                s2.close()
                            }
                        }
                        is PhotoSearch.Opened.Failed -> step("reload", false, "code=${o.code} text=${q(o.text)}", failurePath = true)
                    }
                } catch (t: Throwable) {
                    step("reload", false, "error=${what(t)}", failurePath = true)
                }
            }

            // missing_bundle
            try {
                val missing = File(files, "eg2search-check-missing.litertlm").also { it.delete() }
                val dir = "/sdcard/Android/data/${ctx.packageName}/files"
                when (val o = PhotoSearch.open(missing, cache, backends, nativeDir)) {
                    is PhotoSearch.Opened.Failed -> step("missing_bundle",
                        o.code == PhotoSearch.BUNDLE_MISSING && o.text.contains(missing.name) && o.text.contains(dir),
                        "code=${o.code} text=${q(o.text)}", failurePath = true)
                    is PhotoSearch.Opened.Ready -> {
                        o.search.close()
                        step("missing_bundle", false, "loaded a file that is not there", failurePath = true)
                    }
                }
            } catch (t: Throwable) {
                step("missing_bundle", false, "error=${what(t)}", failurePath = true)
            }

            // permission: the mic path without RECORD_AUDIO
            try {
                if (Mic.granted(ctx)) {
                    step("permission", false, "granted=true: revoke it before the run (adb shell pm revoke ${ctx.packageName} " +
                        "android.permission.RECORD_AUDIO)", failurePath = true)
                } else {
                    val r = Mic.record(ctx, keepGoing = { false })
                    val text = (r as? Mic.Result.Denied)?.text
                    step("permission", r is Mic.Result.Denied && !text.isNullOrBlank(),
                        "granted=false result=${r.javaClass.simpleName} text=${q(text ?: "")}", failurePath = true)
                }
            } catch (t: Throwable) {
                step("permission", false, "error=${what(t)}", failurePath = true)
            }
        } finally {
            search?.close()
            val fileGone = imported?.let { it.delete() || !it.exists() }
            val rowGone = mediaUri?.let { runCatching { ctx.contentResolver.delete(it, null, null) }.getOrDefault(-1) }
            Log.i(TAG, "CLEANUP imported=${imported?.name} deleted=$fileGone media_rows_deleted=$rowGone")
            Log.i(TAG, "RESULT ok=${failures.isEmpty()} steps=$steps failed=${list(failures)} failure_paths_ok=${failurePaths.size} " +
                "failure_paths=${list(failurePaths)} model=${PhotoSearch.MODEL_ID} bundle=${bundle.name} backends=${q(backends.line())} " +
                "runtime=litertlm-android ${BuildConfig.LITERTLM_VERSION} device=${Build.MODEL} soc=${Build.SOC_MODEL} " +
                "build=${Build.DISPLAY} thermal=$thermalBefore->${power.currentThermalStatus}")
        }
        assertTrue("failed steps: $failures (adb logcat -d -s hfmodels-check)", failures.isEmpty())
    }

    /** The clips to score: each wav in files/queries/ (or the queries argument) with a gold photo in queries.json. */
    private fun clips(files: File): List<Pair<File, String>> {
        val dir = File(files, "queries")
        val golds = runCatching {
            val doc = JSONObject(File(dir, "queries.json").readText())
            val m = HashMap<String, String>()
            for (key in listOf("queries", "decoys")) {
                val arr = doc.optJSONArray(key) ?: continue
                for (k in 0 until arr.length()) {
                    val o = arr.getJSONObject(k)
                    if (!o.isNull("gold")) m[o.getString("file")] = o.getString("gold")
                }
            }
            m
        }.getOrDefault(emptyMap())
        val names = args.getString("queries")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: dir.listFiles { f -> f.name.endsWith(".wav") }?.map { it.name }?.sorted().orEmpty()
        return names.mapNotNull { n -> golds[n]?.let { File(dir, n) to it } }
    }

    /** [source] enlarged 2x, saved as JPEG into MediaStore (Pictures/eg2search-check) as this app's own image. */
    private fun insertEnlarged(source: File): Triple<Uri, String, Int> {
        val b = requireNotNull(BitmapFactory.decodeFile(source.path)) { "cannot decode ${source.name}" }
        val big = Bitmap.createScaledBitmap(b, b.width * 2, b.height * 2, true)
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "eg2search-check-${source.name}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/eg2search-check")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)) {
            "MediaStore insert returned null"
        }
        requireNotNull(cr.openOutputStream(uri)) { "no output stream for $uri" }.use { check(big.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return Triple(uri, "${big.width}x${big.height}", maxOf(big.width, big.height))
    }

    private fun bounds(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return o.outWidth to o.outHeight
    }

    /** [seconds] of Gaussian noise at [rms] (1.0 = full scale), 16-bit mono at Mic.RATE, from a fixed seed. */
    private fun noise(seconds: Double, rms: Double): ByteArray {
        val n = (seconds * Mic.RATE).toInt()
        val rnd = Random(7)
        val out = ByteArray(n * 2)
        for (k in 0 until n) {
            val v = (rnd.nextGaussian() * rms * 32768).roundToInt().coerceIn(-32768, 32767)
            out[2 * k] = (v and 0xff).toByte()
            out[2 * k + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    /** [seconds] from the middle of a clip (its speech, for the fixture clips). */
    private fun middle(p: Wav.Pcm, seconds: Double): ByteArray {
        val bytes = (seconds * p.sampleRate).toInt() * 2
        val from = ((p.data.size - bytes) / 2) and 1.inv()
        return p.data.copyOfRange(from, from + bytes)
    }

    private fun desc(a: PhotoSearch.Answer?) = when (a) {
        null -> "none"
        is PhotoSearch.Answer.Refused -> "refused:${a.reason.word}(rms=${f5(a.rms)},seconds=${f2(a.seconds)})"
        is PhotoSearch.Answer.Found -> "found:${a.matches.firstOrNull()?.photo?.id}(rms=${f5(a.rms)},seconds=${f2(a.seconds)})"
    }

    /** A list as one RESULT field: no spaces, so the line splits on them. */
    private fun list(xs: List<String>) = xs.joinToString(",", "[", "]")

    private fun q(s: String) = "\"" + s.take(300).replace("\n", " | ").replace("\"", "'") + "\""

    private fun what(t: Throwable) = "${t.javaClass.simpleName} reason=${q(t.message ?: "")}"

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun f4(v: Double) = String.format(Locale.US, "%.4f", v)
    private fun f5(v: Double) = String.format(Locale.US, "%.5f", v)

    private companion object {
        const val TAG = "hfmodels-check"
    }
}
