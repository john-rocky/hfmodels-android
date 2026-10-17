package io.github.johnrocky.hfmodels.litert

import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.decide.Question
import io.github.johnrocky.hfmodels.decide.TypedDecisions
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typed decisions on a model repo as it is on the Hub, through the public API, on a named device: the repo's
 * own `hfmodels.json` (no catalog entry, no development descriptor), its files downloaded by the SDK (or already
 * in this test app's store from an earlier run), the descriptor's own profile order (`backend=auto`: the default
 * profile, then its `fallback_profiles`; an `npu` default falls through to the GPU when this test APK does not
 * package Qualcomm's runtime, docs/api.md "NPU"), and the repo's own example request answered and compared
 * with the publisher's expected answers. In one process and one load:
 *   1. load: the descriptor's origin and commit, the profile that came up, the fallback history, compile_ms,
 *      what was downloaded;
 *   2. tokens: each question's sequence length (this module's builder) summed vs the expected file's
 *      `usage.input_tokens`, and whether the state was cut;
 *   3. answers: `decide(state, questions)` vs the expected file: the same argmax per question (the choice key,
 *      noul at 0.5, the score level), max |dp| over every option's probability (`dp_tol`), act_probability;
 *   4. timing: `decide(state, all questions)` warm, repeated; the median total and per-question ms;
 *   5. release.
 * One RESULT line per step under tag `hfmodels-decide`. Arguments: repo (default
 * litert-community/Phocinae-Largha-150M-LiteRT), revision (default: the branch head), variant (default: the
 * descriptor's default variant), backend (auto | npu | gpu | cpu; default auto), example (the asset dir with
 * request.json and expected.json, default phocinae: the repo's examples/request.json and
 * examples/run_example.expected.json, desktop CPU, window 384, int8emb), dp_tol (default 0.02, the model lane's
 * bar), repeats (timing, default 5), network (any | offline, default any).
 *
 *   GATE_TEST=HubRepoDecideDeviceTest GATE_TAG=decide-phocinae tools/decide_gate.sh L384_embeds auto
 */
@RunWith(AndroidJUnit4::class)
class HubRepoDecideDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val args = InstrumentationRegistry.getArguments()
    private val repo = args.getString("repo") ?: "litert-community/Phocinae-Largha-150M-LiteRT"
    private val revision: String? = args.getString("revision")
    /** `default` (the gate script always passes one) means none: the descriptor's default variant. */
    private val variant: String? = args.getString("variant")?.takeIf { it != "default" }
    private val backend = args.getString("backend") ?: "auto"
    private val example = args.getString("example") ?: "phocinae"
    private val dpTol = args.getString("dp_tol")?.toDouble() ?: 0.02
    private val repeats = args.getString("repeats")?.toInt() ?: 5
    private val network = if (args.getString("network") == "offline") NetworkPolicy.Offline else NetworkPolicy.Any
    private val failures = ArrayList<String>()
    /** The variant and profile every RESULT line names: the requested ones until the load says which it opened. */
    private var openedVariant = variant ?: "default"
    private var profile = "none"
    private var sdk = "?"

    private fun result(step: String, ok: Boolean, detail: String) {
        Log.i(TAG, "RESULT step=$step ok=$ok repo=$repo variant=$openedVariant backend=$backend profile=$profile $detail")
        if (!ok) failures += "$step: $detail"
    }

    @Test fun loadAnswerTimingRelease(): Unit = runBlocking {
        val models = HfModels(ctx)
        val policy = when (backend) {
            "cpu" -> BackendPolicy.Require(BackendKind.CPU); "gpu" -> BackendPolicy.Require(BackendKind.GPU)
            "npu" -> BackendPolicy.Require(BackendKind.NPU); else -> BackendPolicy.Auto
        }
        val opts = LoadOptions(backendPolicy = policy, networkPolicy = network)
        val ref = ModelRef(repo, revision = revision, variant = variant)
        val power = ctx.getSystemService(PowerManager::class.java)
        val thermalBefore = power.currentThermalStatus
        Log.i(TAG, "device=${Build.MODEL} soc=${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL} build=${Build.DISPLAY} android=${Build.VERSION.RELEASE} litert=${BuildConfig.LITERT_VERSION} repo=$repo revision=${revision ?: "head"} variant=${variant ?: "default"} backend=$backend network=$network npu_libs=${LiteRtNpu.ready(ctx)} thermal=$thermalBefore")
        val request = Json.parseObject(asset("$example/request.json"))
        val expected = Json.parseObject(asset("$example/expected.json"))
        val state = request["state"] ?: throw IllegalArgumentException("request.json has no state")
        val questions = (request["questions"] as Map<*, *>).entries.associate { (k, v) -> k.toString() to Question.fromMap(v as Map<*, *>) }
        val expAnswers = (expected["answers"] as Map<*, *>).entries.associate { (k, v) -> k.toString() to (v as Map<*, *>) }
        val expUsage = expected["usage"] as? Map<*, *>
        var model: TypedDecisions? = null
        try {
            // 1. load
            val events = ArrayList<LoadEvent>()
            val t0 = SystemClock.elapsedRealtime()
            val m = try {
                models.fromPretrained(ref, EncoderDecisions, opts) { events += it }
            } catch (t: Throwable) {
                result("load", false, "error=${what(t)} load_ms=${SystemClock.elapsedRealtime() - t0} events=${events.joinToString(",") { it.javaClass.simpleName }}")
                throw t
            }
            model = m
            val loadMs = SystemClock.elapsedRealtime() - t0
            openedVariant = m.info.variantId
            profile = m.info.profileId
            sdk = m.info.sdkVersion
            val compileMs = m.info.notes.firstNotNullOfOrNull { Regex("compile_ms=(\\d+)").find(it)?.groupValues?.get(1) } ?: "?"
            val downloaded = events.filterIsInstance<LoadEvent.DownloadStarted>().sumOf { it.totalBytes }
            result("load", events.lastOrNull() is LoadEvent.Ready,
                "commit=${m.info.commit} descriptor=${m.info.descriptorOrigin} binding=${m.info.bindingSource} verification=${m.info.verification} " +
                    "policy=$policy requested=${m.info.components["inference"]?.requested} initialized=${m.info.components["inference"]?.initialized} " +
                    "compile_ms=$compileMs load_ms=$loadMs downloaded_bytes=$downloaded downloads=${events.count { it is LoadEvent.DownloadStarted }} " +
                    "fallback=${q(m.info.fallbackHistory.joinToString(" | "))} excluded=${m.info.excludedProfiles} window=${m.limits.windowTokens} head=${m.limits.headTokens} " +
                    "files=${m.info.files.keys.sorted()} notes=${q(m.info.notes.joinToString(" | "))}")

            // 2. tokens: each question's sequence as this module builds it, vs the expected file's usage
            val impl = m as LiteRtDecisionModel
            val builder = (impl.contract as MarkerContract).builder
            val stateIds = impl.contract.stateIds(state)
            var tokens = 0
            var cut = false
            val perQuestion = ArrayList<String>()
            for ((id, q) in questions) {
                val b = builder.build(q, stateIds)
                tokens += b.ids.size
                cut = cut || b.stateTruncated
                perQuestion += "$id=${b.ids.size}(markers ${b.markers.size}, state ${b.stateTokens})"
            }
            val expTokens = (expUsage?.get("input_tokens") as? Number)?.toInt()
            val expCut = expUsage?.get("state_truncated") as? Boolean
            result("tokens", (expTokens == null || expTokens == tokens) && (expCut == null || expCut == cut),
                "input_tokens=$tokens expected=${expTokens ?: "n/a"} state_tokens=${stateIds.size} state_truncated=$cut expected_truncated=${expCut ?: "n/a"} per_question=${perQuestion.joinToString(" ")}")

            // 3. answers vs the publisher's expected file
            val d = m.decide(state, questions)
            var argmaxEqual = 0
            var maxDp = 0.0
            var maxDact = 0.0
            val lines = ArrayList<String>()
            for ((id, q) in questions) {
                val got = d.answers.getValue(id)
                val exp = expAnswers[id] ?: throw IllegalArgumentException("expected.json has no answer for '$id'")
                val gotP: Map<String, Double>
                val expP: Map<String, Double>
                val gotArg: String
                val expArg: String
                when (got) {
                    is Answer.Choice -> {
                        gotP = got.probabilities; expP = numbers(exp["probabilities"])
                        gotArg = got.choice; expArg = exp["choice"].toString()
                    }
                    is Answer.Score -> {
                        gotP = got.probabilities; expP = numbers(exp["probabilities"])
                        gotArg = gotP.maxByOrNull { it.value }!!.key
                        expArg = (exp["level"] as? Number)?.toInt()?.toString() ?: expP.maxByOrNull { it.value }!!.key
                    }
                    is Answer.Noul -> {
                        gotP = mapOf("true" to got.noul, "false" to 1.0 - got.noul)
                        val pt = ((exp["probability_true"] ?: exp["noul"]) as? Number)?.toDouble() ?: throw IllegalArgumentException("expected noul '$id' has no probability_true")
                        expP = mapOf("true" to pt, "false" to 1.0 - pt)
                        gotArg = (got.noul >= 0.5).toString(); expArg = ((exp["noul"] as? Boolean) ?: (pt >= 0.5)).toString()
                    }
                }
                val dp = expP.entries.maxOf { (k, p) -> abs(p - (gotP[k] ?: throw IllegalArgumentException("answer '$id' has no option '$k'"))) }
                val gotAct = ((got.extras["action"] as? Map<*, *>)?.get("act_probability") as? Number)?.toDouble()
                val expAct = ((exp["action"] as? Map<*, *>)?.get("act_probability") as? Number)?.toDouble()
                val dAct = if (gotAct != null && expAct != null) abs(gotAct - expAct) else 0.0
                if (gotArg == expArg) argmaxEqual++
                maxDp = maxOf(maxDp, dp); maxDact = maxOf(maxDact, dAct)
                lines += "$id: type=${q.javaClass.simpleName.lowercase()} argmax=$gotArg expected=$expArg same=${gotArg == expArg} max_dp=${"%.4f".format(dp)} p=${fmt(gotP)} expected_p=${fmt(expP)} act=${gotAct ?: "n/a"} expected_act=${expAct ?: "n/a"} ms=${"%.1f".format(d.timing.questionMs[questions.keys.indexOf(id)])}"
            }
            for (l in lines) Log.i(TAG, l)
            result("answers", argmaxEqual == questions.size && maxDp <= dpTol,
                "questions=${questions.size} argmax_equal=$argmaxEqual/${questions.size} max_dp=${"%.4f".format(maxDp)} dp_tol=$dpTol max_dact=${"%.4f".format(maxDact)} " +
                    "state_tokens=${d.stateTokens} truncated=${d.truncated} first_call_total_ms=${"%.1f".format(d.timing.totalMs)} first_call_question_ms=${d.timing.questionMs.joinToString(",") { "%.1f".format(it) }} " +
                    "expected_model=${expected["model"]} json=${q(d.toJson())}")

            // 4. timing, warm
            repeat(2) { m.decide(state, questions) }
            val totals = ArrayList<Double>()
            val perQ = List(questions.size) { ArrayList<Double>() }
            val stateMs = ArrayList<Double>()
            repeat(repeats) {
                val r = m.decide(state, questions)
                totals += r.timing.totalMs; stateMs += r.timing.stateMs
                for (i in r.timing.questionMs.indices) perQ[i] += r.timing.questionMs[i]
            }
            fun median(v: List<Double>) = v.sorted()[v.size / 2]
            result("timing", true, "repeats=$repeats questions=${questions.size} total_ms_median=${"%.1f".format(median(totals))} total_ms_min=${"%.1f".format(totals.min())} total_ms_max=${"%.1f".format(totals.max())} " +
                "question_ms_median=${perQ.joinToString(",") { "%.1f".format(median(it)) }} state_tokenize_ms_median=${"%.2f".format(median(stateMs))} thermal=$thermalBefore->${power.currentThermalStatus}")

            // 5. release
            val t2 = SystemClock.elapsedRealtime()
            m.closeAndJoin()
            result("release", true, "close_ms=${SystemClock.elapsedRealtime() - t2}")
            model = null
        } catch (t: Throwable) {
            Log.e(TAG, "failed", t)
            failures += "exception: ${what(t)}"
        } finally {
            runCatching { model?.closeAndJoin() }
            models.closeAndJoin()
        }
        Log.i(TAG, "RESULT ok=${failures.isEmpty()} repo=$repo variant=$openedVariant backend=$backend profile=$profile sdk=$sdk litert=${BuildConfig.LITERT_VERSION} device=${Build.MODEL} soc=${Build.SOC_MODEL} build=${Build.DISPLAY} thermal=$thermalBefore->${power.currentThermalStatus} failures=${failures.joinToString(" | ")}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun asset(path: String): String = testCtx.assets.open(path).bufferedReader().use { it.readText() }

    private fun numbers(o: Any?): Map<String, Double> = (o as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to (v as Number).toDouble() } ?: throw IllegalArgumentException("expected probabilities missing")

    private fun fmt(p: Map<String, Double>) = p.entries.joinToString(",", "{", "}") { (k, v) -> "$k=${"%.4f".format(v)}" }

    private fun q(s: String) = "\"" + s.replace("\n", " ").replace("\"", "'") + "\""

    /** An error as one RESULT field: the SDK's code and reason, or the exception's class and message. */
    private fun what(t: Throwable) = if (t is ModelException) "${t.code} reason=${q(t.reason)} details=${t.details}" else "${t.javaClass.simpleName} reason=${q(t.message ?: "")}"

    companion object {
        const val TAG = "hfmodels-decide"
    }
}
