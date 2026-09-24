package io.github.johnrocky.hfmodels.litertlm

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stock-runtime check for the GPU rows that disagree with the CPU rows on a phone: the published
 * LiteRT-LM Kotlin AAR only (no scoring binding, no patched JNI), one conversation per prompt with
 * the bundle's own prompt template, top-1 sampling, a few output tokens. The first letter of the
 * answer on the GPU is compared with the CPU's for the same prompt.
 *
 * Arguments: model (bundle path on the device), backend (gpu | cpu), prompts (device path of a JSON
 * array of {id, system, user, cpu_letter, gpu_letter, kind}), maxTokens (default 4), rows (cap).
 * Output: one `ROW` line per prompt and one `RESULT` line under tag `hfmodels-greedy`, and the same
 * rows as `files/greedy/<model>-<backend>.jsonl` in the test app's files dir.
 */
@RunWith(AndroidJUnit4::class)
class GpuCpuGreedyDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val modelPath = args.getString("model") ?: "/data/local/tmp/hfmodels/qwen3_0_6b_mixed_int4.litertlm"
    private val backend = args.getString("backend") ?: "gpu"
    private val promptsPath = args.getString("prompts") ?: "/data/local/tmp/hfmodels/stock_prompts_qwen3.json"
    private val maxTokens = args.getString("maxTokens")?.toInt() ?: 4
    private val rowLimit = args.getString("rows")?.toInt() ?: Int.MAX_VALUE

    @Test fun greedyFirstLetter() {
        assertTrue("push the bundle to $modelPath first", File(modelPath).isFile)
        assertTrue("push the prompts to $promptsPath first", File(promptsPath).isFile)
        val prompts = JSONArray(File(promptsPath).readText())
        val outDir = File(ctx.filesDir, "greedy").apply { mkdirs() }
        val outFile = File(outDir, "${File(modelPath).nameWithoutExtension}-$backend.jsonl").apply { writeText("") }
        Log.i(TAG, "device=${Build.MODEL} build=${Build.DISPLAY} android=${Build.VERSION.RELEASE} runtime=litertlm-android:${BuildConfig.LITERTLM_VERSION} backend=$backend model=$modelPath prompts=${prompts.length()} maxTokens=$maxTokens sampler=topK1")
        val engine = Engine(EngineConfig(modelPath = modelPath, backend = if (backend == "cpu") Backend.CPU() else Backend.GPU(), maxNumTokens = 1024, cacheDir = File(ctx.cacheDir, "litertlm").apply { mkdirs() }.absolutePath))
        val t0 = System.nanoTime()
        engine.initialize()
        Log.i(TAG, "init_ms=${(System.nanoTime() - t0) / 1_000_000}")
        var checked = 0; var letterFound = 0; var sameAsCpuProbe = 0; var sameAsGpuProbe = 0
        val failures = ArrayList<String>()
        try {
            for (i in 0 until prompts.length()) {
                if (checked >= rowLimit) break
                val p = prompts.getJSONObject(i)
                val id = p.getString("id")
                val config = ConversationConfig(
                    systemInstruction = Contents.of(p.getString("system")),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0),
                    thinkingConfig = ThinkingConfig(false),
                )
                val conv = engine.createConversation(config)
                val ts = System.nanoTime()
                val text = try {
                    val m = conv.sendMessage(p.getString("user"), maxOutputToken = maxTokens, thinkingConfig = ThinkingConfig(false))
                    m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                } catch (t: Throwable) {
                    failures += "$id: ${t.javaClass.simpleName}: ${t.message}"; "<error ${t.javaClass.simpleName}>"
                } finally { runCatching { conv.close() } }
                val ms = (System.nanoTime() - ts) / 1_000_000
                val letter = text.trimStart().firstOrNull()?.takeIf { it in 'A'..'P' }?.toString() ?: ""
                checked++
                if (letter.isNotEmpty()) letterFound++
                val cpuL = p.optString("cpu_letter"); val gpuL = p.optString("gpu_letter")
                if (letter.isNotEmpty() && letter == cpuL) sameAsCpuProbe++
                if (letter.isNotEmpty() && letter == gpuL) sameAsGpuProbe++
                val line = "ROW id=${id.take(8)} kind=${p.optString("kind")} letter='$letter' text=${text.replace("\n", "\\n").take(24).let { "'$it'" }} probe_cpu=$cpuL probe_gpu=$gpuL ms=$ms"
                Log.i(TAG, line)
                outFile.appendText(org.json.JSONObject().put("id", id).put("kind", p.optString("kind")).put("letter", letter).put("text", text).put("probe_cpu", cpuL).put("probe_gpu", gpuL).put("ms", ms).toString() + "\n")
            }
        } finally {
            runCatching { engine.close() }
        }
        Log.i(TAG, "RESULT step=greedy ok=${failures.isEmpty() && checked > 0} backend=$backend runtime=litertlm-android:${BuildConfig.LITERTLM_VERSION} model=${File(modelPath).name} rows=$checked letter_found=$letterFound same_as_cpu_probe=$sameAsCpuProbe same_as_gpu_probe=$sameAsGpuProbe failures=${failures.size} out=${outFile.path}")
        assertTrue(failures.joinToString(" | "), failures.isEmpty() && checked > 0)
    }

    companion object { const val TAG = "hfmodels-greedy" }
}
