package io.github.johnrocky.hfmodels.litertlm

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.BundledCatalog
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.InputKind
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.Tasks
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A3: every bundled catalog entry through the real product path on a named device —
 * `inspect` (Hub) -> `download` (Hub, Range, sha256) -> `prepare` per profile -> one generation
 * (and one image turn when the profile enables IMAGE). Files are evicted after each entry
 * unless `-e keep true`. Instrumentation args:
 *   entries   comma-separated model ids to gate (default: all)
 *   profiles  comma-separated profile ids (default: every profile of the default variant)
 *   variants  comma-separated variant ids (default: the default variant)
 *   image     path of a test image on the device (default /data/local/tmp/hfmodels/sample.png)
 * One RESULT line per (entry, variant, profile) under tag "hfmodels-a3"; tools/gate_to_verification.py
 * turns them into verification records. Times are one-shot wall clock.
 * A load with a thinking channel passes only when none of the channel markers leaked into the text,
 * and, for a model that reasons by default, when the reasoning arrived in Message.channels
 * (thought_chars / thought_chunks are logged).
 */
@RunWith(AndroidJUnit4::class)
class CatalogGateTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val only = args.getString("entries")?.split(',')?.map { it.trim() }?.toSet()
    private val profiles = args.getString("profiles")?.split(',')?.map { it.trim() }?.toSet()
    private val variants = args.getString("variants")?.split(',')?.map { it.trim() }?.toSet()
    private val keep = args.getString("keep") == "true"
    private val image = File(args.getString("image") ?: "/data/local/tmp/hfmodels/sample.png")

    @Test fun gateEveryEntry(): Unit = runBlocking {
        val catalog = BundledCatalog.load(ctx)
        val models = HfModels(ctx)
        Log.i(TAG, "START device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} build=${Build.DISPLAY} litertlm=${BuildConfig.LITERTLM_VERSION} catalog=${catalog.id} entries=${catalog.entries.map { it.modelId }}")
        var failures = 0
        for (entry in catalog.entries) {
            if (only != null && entry.modelId !in only) continue
            val variantIds = variants ?: setOf(entry.descriptor.defaultVariant)
            for (variantId in variantIds) {
                val variant = entry.descriptor.variant(variantId) ?: continue
                val profileIds = profiles ?: variant.profiles.map { it.id }.toSet()
                var plan: io.github.johnrocky.hfmodels.ModelPlan<ChatModel>? = null
                for (profileId in profileIds) {
                    if (variant.profile(profileId) == null) continue
                    val label = "model=${entry.modelId} commit=${entry.modelCommit.take(8)} variant=$variantId profile=$profileId"
                    val t0 = SystemClock.elapsedRealtime()
                    try {
                        val ref = ModelRef(entry.modelId, revision = entry.modelCommit, variant = variantId)
                        val opts = LoadOptions(backendPolicy = BackendPolicy.RequireProfile(profileId))
                        val p = models.inspect(ref, Tasks.Chat, opts)
                        plan = p
                        Log.i(TAG, "PLAN $label files=${p.files.map { "${it.path}:${it.bytes}:${if (it.cached) "cached" else "fetch"}" }} descriptor=${p.descriptorOrigin.repo}@${p.descriptorOrigin.commit.take(8)} sha=${p.descriptorSha256.take(8)}")
                        var last = 0L
                        val td = SystemClock.elapsedRealtime()
                        val local = models.download(p) { e ->
                            if (e is LoadEvent.Downloading && e.bytes - last >= 200L * 1024 * 1024) { last = e.bytes; Log.i(TAG, "DL $label bytes=${e.bytes}/${e.totalBytes}") }
                        }
                        val downloadMs = SystemClock.elapsedRealtime() - td
                        val tp = SystemClock.elapsedRealtime()
                        val model = models.prepare(local)
                        val prepareMs = SystemClock.elapsedRealtime() - tp
                        try {
                            val s = model.createConversation(ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant.")))
                            val r = collect(s, Contents.of(Content.Text(PROMPT)))
                            var imageReply = ""
                            if (InputKind.IMAGE in model.enabledInputs && image.isFile) {
                                val s2 = model.createConversation()
                                imageReply = collect(s2, Contents.of(Content.ImageFile(image.absolutePath), Content.Text("In one short sentence, what is in this picture?"))).text
                                s2.closeAndJoin()
                            }
                            s.closeAndJoin()
                            val thinking = model.thinking
                            val markers = thinking.channels.flatMap { listOf(it.start, it.end) }.map { it.trim() }.filter { it.isNotEmpty() }
                            val thinkingOk = markers.none { r.text.contains(it) } && (!thinking.reasonsByDefault || r.thought.isNotBlank())
                            val ok = r.text.contains("42") && thinkingOk && (InputKind.IMAGE !in model.enabledInputs || !image.isFile || imageReply.isNotBlank())
                            if (!ok) failures++
                            Log.i(TAG, "RESULT ok=$ok $label download_ms=$downloadMs prepare_ms=$prepareMs first_chunk_ms=${r.firstChunkMs} gen_ms=${r.totalMs} chunks=${r.chunks} " +
                                "initialized=${model.info.components.map { "${it.key}=${it.value.initialized}" }} fallback=${model.info.fallbackHistory} reply=${q(r.text)} image_reply=${q(imageReply)} " +
                                "thinking=${thinking.source}/${thinking.prefilled}/${if (thinking.reasonsByDefault) "default" else "off"} thought_chars=${r.thought.length} thought_chunks=${r.thoughtChunks} thought=${q(r.thought)} total_ms=${SystemClock.elapsedRealtime() - t0} " +
                                "sdk=${model.info.sdkVersion} runtime=${model.info.runtime} ${model.info.runtimeVersion} device=${Build.MODEL} build=${Build.DISPLAY}")
                        } finally {
                            model.closeAndJoin()
                        }
                    } catch (t: Throwable) {
                        failures++
                        Log.e(TAG, "RESULT ok=false $label error=${q("${t.javaClass.simpleName}: ${t.message}")} total_ms=${SystemClock.elapsedRealtime() - t0} device=${Build.MODEL} build=${Build.DISPLAY} runtime=litert_lm ${BuildConfig.LITERTLM_VERSION}", t)
                    }
                }
                if (!keep && plan != null) {
                    val freed = runCatching { models.evict(plan) }.getOrElse { -1 }
                    // The runtime's compile / weight caches (about one model size each) live under cacheDir.
                    val cache = File(ctx.cacheDir, "hfmodels")
                    val cacheBytes = cache.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    cache.deleteRecursively()
                    Log.i(TAG, "EVICT model=${entry.modelId} variant=$variantId freed=$freed cache_freed=$cacheBytes free_after=${android.os.StatFs(ctx.filesDir.path).availableBytes}")
                }
            }
        }
        models.closeAndJoin()
        Log.i(TAG, "DONE failures=$failures")
        assertTrue("$failures gate failure(s); see logcat -s hfmodels-a3", failures == 0)
    }

    private class Collected(val text: String, val chunks: Int, val firstChunkMs: Long, val totalMs: Long, val thought: String, val thoughtChunks: Int)
    /** Text and channel content are both incremental: append each chunk's piece. */
    private suspend fun collect(s: ChatSession, contents: Contents): Collected {
        val sb = StringBuilder(); val th = StringBuilder(); var n = 0; var first = -1L; var thoughtChunks = 0
        val t0 = SystemClock.elapsedRealtime()
        withTimeout(600_000) { s.stream(contents).collect { m -> if (n == 0) first = SystemClock.elapsedRealtime() - t0; sb.append(m.text); m.channels.values.firstOrNull()?.let { th.append(it); thoughtChunks++ }; n++ } }
        return Collected(sb.toString(), n, first, SystemClock.elapsedRealtime() - t0, th.toString(), thoughtChunks)
    }
    private fun q(s: String) = "\"" + s.take(120).replace("\n", " ").replace("\"", "'") + "\""

    private companion object {
        const val TAG = "hfmodels-a3"
        const val PROMPT = "What is 17 + 25? Answer briefly."
    }
}
