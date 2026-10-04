package io.github.johnrocky.hfmodels.check

import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.samples.finder.DecisionModels
import io.github.johnrocky.hfmodels.samples.finder.Facet
import io.github.johnrocky.hfmodels.samples.finder.Filters
import io.github.johnrocky.hfmodels.samples.finder.Finder
import io.github.johnrocky.hfmodels.samples.finder.Recipes
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device check for samples/finder. On the connected phone and in the app's own process it proves what the screen relies
 * on: the model loads by its id (its files from the app's private storage, a copy pushed to its external files dir, or a
 * download), the 40 sentences of the round-18 sieve (`f1_filter.jsonl`, this test's asset) go through the app's own
 * code (Finder.ask: one decide() with the five questions) and every one of the 200 answers is compared with the sieve's
 * gold and with the Mac host's answer (`gliner_f1_v1.jsonl`, the sieve's GLiNER2.5-Decide run); the recording mode's two
 * sentences and the three of Try set the filters they are expected to and leave 2 to 5 recipes; release returns.
 *
 *   adb push gliner25_decide_s128_npu_wfp16.tflite word_embeddings_fp16.bin tokenizer.json \
 *       /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.finder/files/    # optional, after the app's first start: else the load downloads 0.93 GB
 *   ./gradlew :samples:finder:assembleDebug :samples:finder:assembleDebugAndroidTest
 *   adb install -r samples/finder/build/outputs/apk/debug/finder-debug.apk
 *   adb install -r samples/finder/build/outputs/apk/androidTest/debug/finder-debug-androidTest.apk
 *   adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.FinderDeviceCheck -e backend npu \
 *       io.github.johnrocky.hfmodels.samples.finder.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s hfmodels-check | grep RESULT
 *
 * `connectedDebugAndroidTest` runs it as well, but uninstalls the app afterwards, and the imported model with it.
 * One `RESULT` line per step, then one per answer that differs from the Mac host's (`step=diff`), and last
 * `RESULT ok=…`: true when every step passed, and the filter step passes when all 200 answers are the Mac host's, or,
 * with every differing answer listed, when at least 180 of the 200 are the gold. Every line names the variant and the
 * profile. Arguments: backend=npu|gpu|cpu (npu: variant s128_npu_wfp16 on the NPU, which needs Qualcomm's runtime in
 * the app, README.md "NPU"; gpu, cpu: variant s128_wfp16 on that backend; default: the app's own choice,
 * DecisionModels.choose), network=offline|any (default any; offline needs the id bound by an earlier load).
 */
@RunWith(AndroidJUnit4::class)
class FinderDeviceCheck {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val backend: String? = args.getString("backend")
    private val network = if (args.getString("network") == "offline") NetworkPolicy.Offline else NetworkPolicy.Any

    @Test fun loadFilterRelease(): Unit = runBlocking {
        val failures = ArrayList<String>()
        val d = DecisionModels(ctx)
        val choice = DecisionModels.choose(d.npuRuntime, backend)
        // The variant and profile every line names: the requested ones until the load says which it opened.
        var variant = choice.variant
        var profile = "none"
        fun step(name: String, ok: Boolean, values: String) {
            if (!ok) failures += name
            Log.i(TAG, "RESULT step=$name ok=$ok model=${DecisionModels.REPO} variant=$variant profile=$profile $values")
        }
        val power = ctx.getSystemService(PowerManager::class.java)
        val thermalBefore = power.currentThermalStatus
        val events = ArrayList<LoadEvent>()
        val t0 = SystemClock.elapsedRealtime()
        val model = try {
            d.load(choice, network) { events += it }.also { m ->
                variant = m.info.variantId
                profile = m.info.profileId
                val compileMs = m.info.notes.firstNotNullOfOrNull { Regex("compile_ms=(\\d+)").find(it)?.groupValues?.get(1) } ?: "?"
                step("load", true, "backend=${backend ?: "default"} policy=${choice.policy} npu_runtime=${d.npuRuntime} npu_failure=${d.npuFailure?.let { q(it) }} " +
                    "compile_ms=$compileMs load_ms=${SystemClock.elapsedRealtime() - t0} warmup_ms=${d.warmupMs} " +
                    "downloaded=${events.any { it is LoadEvent.DownloadStarted }} network=$network fallback=${m.info.fallbackHistory} commit=${m.info.commit.take(8)} " +
                    "bound=${d.models.boundCommit(DecisionModels.REPO)?.take(8)}")
            }
        } catch (t: Throwable) {
            step("load", false, "backend=${backend ?: "default"} policy=${choice.policy} npu_runtime=${d.npuRuntime} error=${what(t)} load_ms=${SystemClock.elapsedRealtime() - t0}")
            Log.i(TAG, "RESULT ok=false variant=$variant profile=$profile failed=$failures device=${Build.MODEL} build=${Build.DISPLAY}")
            d.models.closeAndJoin()
            assertTrue("load failed: ${what(t)}", false)
            return@runBlocking
        }
        // Any error is recorded as a failure of the step it happened in, so a RESULT ok=true line never follows it.
        var current = "filter"
        try {
            val sieve = jsonl("f1_filter.jsonl")
            val mac = jsonl("gliner_f1_v1.jsonl").associateBy { it["id"] as String }
            val facets = Facet.values().toList()
            var fieldsCorrect = 0
            var sameAsMac = 0
            var queriesAllCorrect = 0
            var falseConstraints = 0
            var unsetFields = 0
            var maxDp = 0.0
            val queryMs = ArrayList<Double>()
            val questionMs = ArrayList<Double>()
            val diffs = ArrayList<String>()
            val misses = ArrayList<String>()
            for (row in sieve) {
                val id = row["id"] as String
                val text = row["text"] as String
                @Suppress("UNCHECKED_CAST")
                val gold = Filters(row["gold"] as Map<String, String>)
                val m = mac.getValue(id)
                @Suppress("UNCHECKED_CAST")
                val macKeys = Filters(m["answers"] as Map<String, String>)
                @Suppress("UNCHECKED_CAST")
                val macP = m["probabilities"] as Map<String, Map<String, Double>>
                val r = Finder.ask(model, text)
                queryMs += r.totalMs
                questionMs += r.questionMs.values
                var allCorrect = true
                for (f in facets) {
                    val got = r.filters[f]
                    if (got == gold[f]) fieldsCorrect++ else { allCorrect = false; misses += "$id.${f.id}:gold=${gold[f]},device=$got" }
                    if (!gold.isSet(f)) { unsetFields++; if (got != f.unset) falseConstraints++ }
                    // The Mac file keys the spice levels by index; the app by filter key.
                    val pm = macP.getValue(f.id).let { p -> if (f == Facet.SPICE) p.mapKeys { Finder.SPICE_KEYS[it.key.toInt()] } else p }
                    val pd = r.probabilities.getValue(f.id)
                    for ((k, v) in pd) maxDp = maxOf(maxDp, abs(v - pm.getValue(k)))
                    if (got == macKeys[f]) sameAsMac++ else diffs += "id=$id field=${f.id} gold=${gold[f]} mac=${macKeys[f]} device=$got " +
                        "p_mac=${top2(pm)} p_device=${top2(pd)} text=${q(text)}"
                }
                if (allCorrect) queriesAllCorrect++
                Log.i(TAG, "q id=$id " + facets.joinToString(" ") { "${it.id}=${r.filters[it]}" } + " gold_ok=${facets.count { r.filters[it] == gold[it] }}/5 " +
                    "same_as_mac=${facets.count { r.filters[it] == macKeys[it] }}/5 total_ms=${r.totalMs} question_ms=${r.questionMs.values} tokens=${r.stateTokens} " +
                    "p=" + r.probabilities.entries.joinToString(" ") { (qid, p) -> "$qid:" + p.entries.joinToString(",", "{", "}") { "${it.key}=${it.value}" } } + " text=${q(text)}")
            }
            val n = sieve.size * facets.size
            val filterOk = sameAsMac == n || fieldsCorrect >= 180
            step("filter", filterOk, "fields_correct=$fieldsCorrect/$n same_as_mac=$sameAsMac/$n queries_all_correct=$queriesAllCorrect/${sieve.size} " +
                "false_constraints=$falseConstraints/$unsetFields query_ms_median=${median(queryMs)} question_ms_median=${median(questionMs)} " +
                "query_ms_p90=${p90(queryMs)} question_ms_p90=${p90(questionMs)} max_dp=$maxDp differing=${diffs.size} misses=$misses")
            for (line in diffs) Log.i(TAG, "RESULT step=diff variant=$variant profile=$profile $line")

            // The recording mode's sentences and Try's: the expected filters, and 2 to 5 recipes left.
            current = "script"
            val recipes = ctx.assets.open(Recipes.ASSET).bufferedReader().use { Recipes.parse(it.readText()) }
            val checks = (Finder.SCRIPT + Finder.EXAMPLES).map { s ->
                val r = Finder.ask(model, s.text)
                val agree = facets.count { r.filters[it] == s.expected[it] }
                val kept = r.filters.keep(recipes)
                Triple(s.id, agree == facets.size && kept.size in 2..5, "${s.id}=$agree/5,kept=${kept.size}:${kept.joinToString("+") { it.id }},ms=${r.totalMs}")
            }
            step("script", checks.all { it.second }, checks.joinToString(" ") { it.third })
        } catch (t: Throwable) {
            step(current, false, "error=${what(t)}")
        } finally {
            // Release: closeAndJoin must return (10 s cap inside the SDK).
            val t2 = SystemClock.elapsedRealtime()
            val released = runCatching { d.release(); d.models.closeAndJoin() }
            val closeMs = SystemClock.elapsedRealtime() - t2
            step("release", released.isSuccess && closeMs < 10_000, "close_ms=$closeMs" + (released.exceptionOrNull()?.let { " error=${what(it)}" } ?: ""))
            Log.i(TAG, "RESULT ok=${failures.isEmpty()} model=${DecisionModels.REPO}@${model.info.commit.take(8)} variant=$variant profile=$profile failed=$failures sdk=${model.info.sdkVersion} runtime=${model.info.runtime} ${model.info.runtimeVersion} " +
                "device=${Build.MODEL} soc=${Build.SOC_MODEL} build=${Build.DISPLAY} thermal=$thermalBefore->${power.currentThermalStatus}")
        }
        assertTrue("failed steps: $failures (adb logcat -d -s hfmodels-check)", failures.isEmpty())
    }

    /** A JSON-lines asset of this test APK, one object per line. */
    private fun jsonl(name: String): List<Map<String, Any?>> = instrumentation.context.assets.open(name).bufferedReader().use { r ->
        r.readLines().filter { it.isNotBlank() }.map { Json.parseObject(it) }
    }

    private fun top2(p: Map<String, Double>) = p.entries.sortedByDescending { it.value }.take(2).joinToString("/") { "${it.key}:${"%.4f".format(it.value)}" }

    /** The device gate's definitions (GlinerDecideDeviceTest): sorted[n / 2] and sorted[9n / 10]. */
    private fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]
    private fun p90(xs: List<Double>) = xs.sorted()[(xs.size * 9) / 10]

    private fun q(s: String) = "\"" + s.take(160).replace("\n", " ").replace("\"", "'") + "\""

    /** An error as one RESULT field: the SDK's code and reason, or the exception's class and message. */
    private fun what(t: Throwable) = if (t is ModelException) "${t.code} reason=${q(t.reason)}" else "${t.javaClass.simpleName} reason=${q(t.message ?: "")}"

    private companion object {
        const val TAG = "hfmodels-check"
    }
}
