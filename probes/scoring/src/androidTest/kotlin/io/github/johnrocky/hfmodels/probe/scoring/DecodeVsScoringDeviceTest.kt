package io.github.johnrocky.hfmodels.probe.scoring

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SessionConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.exp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the greedy decode of the same prompt pick the same letter as the scoring readout, on the same
 * backend? Separates two explanations of the GPU rows that disagree with the CPU rows on the S26:
 * if the greedy first token on the GPU also differs from the CPU's, the difference is in the GPU
 * forward pass (the logits themselves); if the greedy token agrees with the CPU while the scores
 * differ, the difference is in the scoring path on the GPU.
 *
 * Arguments: model, backend (gpu | cpu), fixture (asset name), ids (comma-separated row id prefixes;
 * default = every row), rows (cap). Per row, in one fresh session with `topK = 1`: the first
 * streamed text of the decode (cancelled after the first chunk), then, in another fresh session, the
 * three letter scores (prefill, checkpoint, score, rewind). Output: RESULT lines under
 * `hfmodels-scoring` and `files/scoring/<fixture>-<backend>.decode.jsonl` in the app's files dir.
 */
@RunWith(AndroidJUnit4::class)
class DecodeVsScoringDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val modelPath = args.getString("model") ?: "/data/local/tmp/hfmodels/qwen3_0_6b_mixed_int4.litertlm"
    private val backend = args.getString("backend") ?: "gpu"
    private val fixtureName = args.getString("fixture") ?: "authored144_qwen3_0_6b_oracle.json"
    private val idFilter = args.getString("ids")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    private val rowLimit = args.getString("rows")?.toInt() ?: Int.MAX_VALUE
    private val outDir = File(ctx.filesDir, "scoring").apply { mkdirs() }
    private val outFile = File(outDir, "${fixtureName.removeSuffix(".json")}-$backend.decode.jsonl")

    private fun softmax(v: DoubleArray): DoubleArray { val m = v.max(); val e = v.map { exp(it - m) }; val s = e.sum(); return e.map { it / s }.toDoubleArray() }
    private fun argmax(v: DoubleArray) = v.indices.maxByOrNull { v[it] }!!

    @Test fun greedyDecodeAgainstScoring() {
        outFile.writeText("")
        assertTrue("push the bundle to $modelPath first", File(modelPath).isFile)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixtureText = runCatching { assets.open(fixtureName).bufferedReader().use { it.readText() } }
            .getOrElse { GZIPInputStream(assets.open("$fixtureName.gz")).bufferedReader().use { it.readText() } }
        val fixture = JSONObject(fixtureText)
        val letters = fixture.getString("letters")
        val stripPrefix = fixture.optString("strip_prefix", "")
        val rows = fixture.getJSONArray("rows")
        Log.i(TAG, "device=${Build.MODEL} build=${Build.DISPLAY} android=${Build.VERSION.RELEASE} backend=$backend model=$modelPath fixture=$fixtureName ids=${idFilter.size} rows=${rows.length()}")
        try { System.loadLibrary("litertlm_jni") } catch (e: UnsatisfiedLinkError) { Log.e(TAG, "loadLibrary: ${e.message}") }
        val engine = Engine(EngineConfig(modelPath = modelPath, backend = if (backend == "cpu") Backend.CPU() else Backend.GPU(), cacheDir = File(ctx.cacheDir, "litertlm").apply { mkdirs() }.path))
        engine.initialize()
        var checked = 0; var decodeLetterFound = 0; var agree = 0; var disagree = 0
        val failures = ArrayList<String>()
        try {
            for (i in 0 until rows.length()) {
                if (checked >= rowLimit) break
                val r = rows.getJSONObject(i)
                val id = r.getString("id")
                if (idFilter.isNotEmpty() && idFilter.none { id.startsWith(it) }) continue
                val rawPrompt = r.getString("prompt")
                val prompt = if (stripPrefix.isNotEmpty() && rawPrompt.startsWith(stripPrefix)) rawPrompt.substring(stripPrefix.length) else rawPrompt
                val n = r.getJSONArray("option_ids").length()
                // 1. greedy decode: the first streamed chunk of a fresh session, top-1 sampling
                val greedy = SessionConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0), applyPromptTemplate = false)
                val first = StringBuilder(); val done = CountDownLatch(1); var error: String? = null
                val s1 = engine.createSession(greedy)
                val td = System.nanoTime()
                try {
                    s1.generateContentStream(listOf(InputData.Text(prompt)), object : ResponseCallback {
                        override fun onNext(response: String) {
                            if (first.isEmpty() && response.isNotEmpty()) { first.append(response); runCatching { s1.cancelProcess() } }
                            else if (first.isNotEmpty() && first.length < 8) first.append(response)
                        }
                        override fun onDone() { done.countDown() }
                        override fun onError(throwable: Throwable) { if (first.isEmpty()) error = "${throwable.javaClass.simpleName}: ${throwable.message}"; done.countDown() }
                    })
                    if (!done.await(120, TimeUnit.SECONDS)) error = "decode timeout"
                } finally { runCatching { s1.close() } }
                val decodeMs = (System.nanoTime() - td) / 1e6
                val text = first.toString()
                val letterChar = text.trimStart().firstOrNull()
                val decodeIdx = letterChar?.let { c -> letters.indexOf(c).takeIf { it in 0 until n } } ?: -1
                // 2. scoring readout of the same prompt: prefill, checkpoint, one score per letter, rewind
                val s2 = engine.createSession(SessionConfig(applyPromptTemplate = false))
                val neg = DoubleArray(n)
                val ts = System.nanoTime()
                try {
                    s2.runPrefill(listOf(InputData.Text(prompt)))
                    s2.saveCheckpoint("q")
                    for (k in 0 until n) { neg[k] = s2.runTextScoring(listOf(letters[k].toString())).scores[0].toDouble(); s2.rewindToCheckpoint("q") }
                } finally { runCatching { s2.close() } }
                val scoreMs = (System.nanoTime() - ts) / 1e6
                val p = softmax(neg); val scoreIdx = argmax(p)
                checked++
                if (decodeIdx >= 0) { decodeLetterFound++; if (decodeIdx == scoreIdx) agree++ else disagree++ }
                val line = JSONObject().put("id", id).put("decode_text", text).put("decode_letter", decodeIdx).put("score_letter", scoreIdx)
                    .put("probabilities", JSONArray(p.toList())).put("logp", JSONArray(neg.toList())).put("decode_ms", decodeMs).put("score_ms", scoreMs).put("decode_error", error)
                outFile.appendText(line.toString() + "\n")
                Log.i(TAG, "ROW id=${id.take(8)} decode='${text.replace("\n", "\\n").take(12)}' decode_letter=$decodeIdx score_letter=$scoreIdx p=${p.joinToString(",") { "%.3f".format(it) }} decode_ms=${"%.0f".format(decodeMs)} score_ms=${"%.0f".format(scoreMs)}${error?.let { " error=$it" } ?: ""}")
            }
            Log.i(TAG, "RESULT step=decode_vs_scoring ok=${checked > 0} backend=$backend model=${File(modelPath).name} fixture=$fixtureName rows=$checked decode_letter_found=$decodeLetterFound agree=$agree disagree=$disagree out=${outFile.path}")
        } catch (t: Throwable) {
            Log.e(TAG, "failed", t); failures += "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { engine.close() }
        }
        assertTrue(failures.joinToString(" | "), failures.isEmpty() && checked > 0)
    }

    companion object { const val TAG = "hfmodels-scoring" }
}
