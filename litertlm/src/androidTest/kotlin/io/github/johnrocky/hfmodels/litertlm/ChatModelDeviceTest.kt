package io.github.johnrocky.hfmodels.litertlm

import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.BundledCatalog
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.Tasks
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * A2 on a named device (`export ANDROID_SERIAL=…; ./gradlew :litertlm:connectedDebugAndroidTest`).
 * The model is side-loaded from /data/local/tmp/hfmodels/qwen25_1_5b_q8.litertlm into the SDK
 * cache through the public importFile (hashed once per install); every load is then Offline with
 * an explicit commit and an explicit descriptor, so no test touches the network.
 * One RESULT line per check under tag "hfmodels-a2"; times are one-shot, not a benchmark.
 * Check i (tools) loads its own model: argument toolModel `functiongemma` (default; FunctionGemma 270M
 * mobile-actions, catalog/dev descriptor as a test-APK asset, file /data/local/tmp/hfmodels/llm/mobile_actions_q8_ekv1024.litertlm)
 * or `gemma4` (Gemma 4 E2B, the bundled catalog's entry, file /data/local/tmp/gemma-4-E2B-it.litertlm); toolPath and
 * toolBackend (cpu | gpu) override the file and the backend.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ChatModelDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = File(InstrumentationRegistry.getArguments().getString("lmPath") ?: "/data/local/tmp/hfmodels/qwen25_1_5b_q8.litertlm")
    private lateinit var models: HfModels

    @Before fun setUp() {
        models = HfModels(ctx)
    }

    /** The Qwen2.5 bundle into the cache (checks a to h; i loads its own model). */
    private suspend fun importQwen() {
        assertTrue("push the model to ${source.path} first", source.isFile)
        val plan = models.inspect(REF, Tasks.Chat, opts())
        val t0 = SystemClock.elapsedRealtime()
        val cached = models.importFile(plan, "weights", source)
        Log.i(TAG, "import ms=${SystemClock.elapsedRealtime() - t0} cached=${cached.path} bytes=${cached.length()} device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} build=${Build.DISPLAY} litertlm=${BuildConfig.LITERTLM_VERSION}")
    }

    private fun opts(policy: BackendPolicy = BackendPolicy.Require(BackendKind.CPU)) =
        LoadOptions(backendPolicy = policy, networkPolicy = NetworkPolicy.Offline, descriptorJson = DESCRIPTOR)

    private suspend fun load(policy: BackendPolicy = BackendPolicy.Require(BackendKind.CPU)): ChatModel {
        importQwen()
        val events = mutableListOf<LoadEvent>()
        val t0 = SystemClock.elapsedRealtime()
        val m = models.fromPretrained(REF, Tasks.Chat, opts(policy)) { events += it }
        Log.i(TAG, "load ms=${SystemClock.elapsedRealtime() - t0} profile=${m.info.profileId} events=${events.map { it.javaClass.simpleName }} notes=${m.info.notes}")
        assertTrue(events.last() is LoadEvent.Ready)
        assertTrue(events.none { it is LoadEvent.DownloadStarted })
        return m
    }

    @Test fun a_fixedPromptStreamsAndASecondTurnWorks() = runBlocking {
        val model = load()
        try {
            assertEquals("cpu", model.info.profileId)
            assertEquals(BackendKind.CPU, model.info.components["language"]!!.initialized)
            assertEquals("UNKNOWN", model.info.components["language"]!!.observed)
            val s = model.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
            val r1 = collect(s.stream(Contents.of(Content.Text(PROMPT))))
            assertTrue("expected streaming, got ${r1.chunks} chunks", r1.chunks > 1)
            assertTrue("expected 42 in '${r1.text}'", r1.text.contains("42"))
            assertEquals(SessionState.READY, s.state)
            val r2 = collect(s.stream(Contents.of(Content.Text("And 17 + 26? Answer briefly."))))
            assertTrue(r2.text.isNotBlank())
            assertEquals(SessionState.READY, s.state)
            s.closeAndJoin(); s.closeAndJoin()
            assertEquals(SessionState.CLOSED, s.state)
            result("turn", "chunks=${r1.chunks} first_chunk_ms=${r1.firstChunkMs} total_ms=${r1.totalMs} reply=${q(r1.text)} second=${q(r2.text)}")
        } finally { model.closeAndJoin() }
    }

    @Test fun b_collectorCancelReachesNativeAndInvalidatesTheSession() = runBlocking {
        val model = load()
        try {
            val s = model.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
            var chunks = 0
            var completed = false
            val job = launch(Dispatchers.Default) { s.stream(Contents.of(Content.Text(LONG_PROMPT))).collect { chunks++ }; completed = true }
            withTimeout(120_000) { while (chunks < 5) delay(20) }
            val generating = cpuOverMs(500)
            assertFalse("reply ended before the cancel", completed)
            job.cancelAndJoin()
            val at = chunks
            delay(300)
            val idle = cpuOverMs(1500)
            assertEquals("chunks after cancel", at, chunks)
            assertTrue("CPU after cancel: ${idle.cpuMs} ms / ${idle.wallMs} ms (generating: ${generating.cpuMs}/${generating.wallMs})", idle.cpuMs < 400)
            assertEquals(SessionState.INVALID, s.state)
            try { collect(s.stream(Contents.of(Content.Text(PROMPT)))); fail() } catch (e: ModelException) { assertEquals(ErrorCode.SESSION_INVALIDATED, e.code) }
            // The model is free again: a new session answers.
            val s2 = model.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
            val r = collect(s2.stream(Contents.of(Content.Text(PROMPT))))
            assertTrue(r.text.contains("42"))
            s.closeAndJoin(); s2.closeAndJoin()
            result("cancel_collector", "chunks_before_cancel=$at cpu_ms_generating=${generating.cpuMs}/${generating.wallMs}ms cpu_ms_after_cancel=${idle.cpuMs}/${idle.wallMs}ms next_session_reply=${q(r.text)}")
        } finally { model.closeAndJoin() }
    }

    @Test fun c_explicitCancelEndsTheFlowWithThePartialText() = runBlocking {
        val model = load()
        try {
            val s = model.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
            val text = StringBuilder(); var n = 0
            val t0 = SystemClock.elapsedRealtime()
            withTimeout(120_000) { s.stream(Contents.of(Content.Text(LONG_PROMPT))).collect { m -> text.append(textOf(m)); if (++n == 5) s.cancel() } }
            val ms = SystemClock.elapsedRealtime() - t0
            assertTrue("expected the flow to end near chunk 5, got $n", n in 5..8)
            delay(300)
            val idle = cpuOverMs(1500)
            assertTrue(idle.cpuMs < 400)
            assertTrue(text.isNotBlank())
            assertEquals(SessionState.INVALID, s.state)
            s.closeAndJoin()
            result("cancel_explicit", "chunks=$n flow_completed_ms=$ms cpu_ms_after_cancel=${idle.cpuMs}/${idle.wallMs}ms partial=${q(text.toString())}")
        } finally { model.closeAndJoin() }
    }

    @Test fun d_slowConsumerEndsWithSlowConsumerAndNativeStops() = runBlocking {
        val model = load()
        try {
            (model as LiteRtLmChatModel).streamBufferChunks = 2
            val s = model.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
            var n = 0
            try {
                withTimeout(120_000) { s.stream(Contents.of(Content.Text(LONG_PROMPT))).collect { n++; delay(4_000) } }
                fail("expected SLOW_CONSUMER")
            } catch (e: ModelException) { assertEquals(ErrorCode.SLOW_CONSUMER, e.code) }
            delay(300)
            val idle = cpuOverMs(1500)
            assertTrue("CPU after SLOW_CONSUMER: ${idle.cpuMs}", idle.cpuMs < 400)
            assertEquals(SessionState.INVALID, s.state)
            s.closeAndJoin()
            result("slow_consumer", "chunks_delivered=$n cpu_ms_after=${idle.cpuMs}/${idle.wallMs}ms")
        } finally { model.closeAndJoin() }
    }

    @Test fun e_secondGenerationIsBusyAndSecondCollectIsRejected() = runBlocking {
        val model = load()
        try {
            val s1 = model.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
            val s2 = model.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
            var n = 0
            val job = launch(Dispatchers.Default) { runCatching { s1.stream(Contents.of(Content.Text(LONG_PROMPT))).collect { n++ } } }
            withTimeout(120_000) { while (n < 3) delay(20) }
            try { collect(s2.stream(Contents.of(Content.Text(PROMPT)))); fail() } catch (e: ModelException) { assertEquals(ErrorCode.MODEL_BUSY, e.code) }
            assertEquals(SessionState.READY, s2.state)
            job.cancelAndJoin()
            val f: Flow<Message> = s2.stream(Contents.of(Content.Text(PROMPT)))
            assertTrue(collect(f).text.contains("42"))
            try { collect(f); fail() } catch (e: ModelException) { assertEquals(ErrorCode.STREAM_ALREADY_COLLECTED, e.code) }
            s1.closeAndJoin(); s2.closeAndJoin()
            result("busy_and_single_collect", "ok")
        } finally { model.closeAndJoin() }
    }

    @Test fun f_closeDuringGenerationReleasesAndPrepareAgainWorks() = runBlocking {
        val model = load()
        val s = model.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
        var n = 0
        val job = launch(Dispatchers.Default) { runCatching { s.stream(Contents.of(Content.Text(LONG_PROMPT))).collect { n++ } } }
        withTimeout(120_000) { while (n < 5) delay(20) }
        val t0 = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) { model.closeAndJoin() }
        val closeMs = SystemClock.elapsedRealtime() - t0
        withTimeout(30_000) { job.join() }
        assertTrue("closeAndJoin during generation took $closeMs ms", closeMs < 10_000)
        assertEquals(SessionState.CLOSED, s.state)
        delay(300)
        val idle = cpuOverMs(1500)
        assertTrue("CPU after close: ${idle.cpuMs}", idle.cpuMs < 400)
        try { model.createConversation(); fail() } catch (e: ModelException) { assertEquals(ErrorCode.MODEL_CLOSED, e.code) }
        model.closeAndJoin() // idempotent
        // The client's single slot is free again.
        val again = load()
        val r = collect(again.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM))).stream(Contents.of(Content.Text(PROMPT))))
        assertTrue(r.text.contains("42"))
        again.closeAndJoin()
        result("close_during_generation", "chunks_at_close=$n close_ms=$closeMs cpu_ms_after_close=${idle.cpuMs}/${idle.wallMs}ms reply_after_reload=${q(r.text)}")
    }

    @Test fun g_inputAndConfigValidationHappenBeforeNativeCalls() = runBlocking {
        val model = load()
        try {
            try { model.createConversation(ConversationConfig(enableResponseFormat = true)); fail() } catch (e: ModelException) { assertEquals(ErrorCode.UNSUPPORTED_CONFIGURATION, e.code) }
            val s = model.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
            try { collect(s.stream(Contents.of(Content.ImageBytes(ByteArray(10)), Content.Text("what is this?")))); fail() } catch (e: ModelException) { assertEquals(ErrorCode.UNSUPPORTED_INPUT, e.code) }
            assertEquals("input failure keeps READY", SessionState.READY, s.state)
            try { collect(s.stream(Contents.of(Content.AudioBytes(ByteArray(10))))); fail() } catch (e: ModelException) { assertEquals(ErrorCode.UNSUPPORTED_INPUT, e.code) }
            val r = collect(s.stream(Contents.of(Content.Text(PROMPT))))
            assertTrue(r.text.contains("42"))
            s.closeAndJoin()
            result("validation", "ok")
        } finally { model.closeAndJoin() }
    }

    @Test fun h_gpuProfileGenerates() = runBlocking {
        val model = load(BackendPolicy.Require(BackendKind.GPU))
        try {
            assertEquals("gpu", model.info.profileId)
            assertEquals(BackendKind.GPU, model.info.components["language"]!!.initialized)
            val s = model.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
            val r = collect(s.stream(Contents.of(Content.Text(PROMPT))))
            assertTrue("expected 42 in '${r.text}'", r.text.contains("42"))
            s.closeAndJoin()
            result("gpu_turn", "chunks=${r.chunks} first_chunk_ms=${r.firstChunkMs} total_ms=${r.totalMs} reply=${q(r.text)}")
        } finally { model.closeAndJoin() }
    }

    @Test fun i_toolCallsComeBackInToolCallsAndTheAppAnswersThem() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val gemma4 = args.getString("toolModel") == "gemma4"
        val file = File(args.getString("toolPath") ?: if (gemma4) "/data/local/tmp/gemma-4-E2B-it.litertlm" else "/data/local/tmp/hfmodels/llm/mobile_actions_q8_ekv1024.litertlm")
        assertTrue("push the model to ${file.path} first", file.isFile)
        val descriptor = if (gemma4) null else InstrumentationRegistry.getInstrumentation().context.assets.open(FG_DESCRIPTOR).bufferedReader().use { it.readText() }
        val repo = if (gemma4) G4_REPO else FG_REPO
        val commit = descriptor?.let { JSONObject(it).getString("revision") } ?: BundledCatalog.load(ctx).defaultBinding(repo)!!.modelCommit
        val ref = ModelRef(repo, revision = commit)
        val backend = if (args.getString("toolBackend") == "gpu") BackendKind.GPU else BackendKind.CPU
        val toolOpts = LoadOptions(backendPolicy = BackendPolicy.Require(backend), networkPolicy = NetworkPolicy.Offline, descriptorJson = descriptor)
        val plan = models.inspect(ref, Tasks.Chat, toolOpts)
        for (f in plan.files) if (!f.cached) models.importFile(plan, f.id, file)
        val model = models.fromPretrained(ref, Tasks.Chat, toolOpts)
        try {
            var executedByRuntime = 0
            val alarm = object : OpenApiTool {
                override fun getToolDescriptionJsonString() = ALARM_TOOL
                override fun execute(paramsJsonString: String): String { executedByRuntime++; return "{}" }
            }
            // automaticToolCalling = true is asked for and overridden: the SDK hands the calls to the app.
            val s = model.createConversation(ConversationConfig(systemInstruction = Contents.of("You are a phone assistant."), tools = listOf(tool(alarm)), automaticToolCalling = true))
            try { collect(s.stream(Message.tool(Contents.of(Content.Text("not a tool response"))))); fail() } catch (e: ModelException) { assertEquals(ErrorCode.INVALID_INPUT, e.code) }
            assertEquals("input failure keeps READY", SessionState.READY, s.state)
            val calls = ArrayList<ToolCall>()
            val said = StringBuilder()
            var chunks = 0
            val t0 = SystemClock.elapsedRealtime()
            withTimeout(120_000) { s.stream(Contents.of(Content.Text(TOOL_PROMPT))).collect { m -> chunks++; calls += m.toolCalls; said.append(textOf(m)) } }
            val callMs = SystemClock.elapsedRealtime() - t0
            // On the record before the checks: a model that answers in text instead is a finding, not only a failure.
            result("tool_call_turn", "model=$repo profile=${model.info.profileId} prompt=${q(TOOL_PROMPT)} calls=${calls.map { "${it.name}${it.arguments}" }} chunks=$chunks ms=$callMs text=${q(said.toString())} executed_by_runtime=$executedByRuntime")
            assertTrue("expected a set_alarm call, got calls=$calls text='$said'", calls.any { it.name == "set_alarm" })
            assertEquals("the runtime ran the tool itself", 0, executedByRuntime)
            assertEquals(SessionState.READY, s.state)
            val answer = Message.tool(Contents.of(calls.map { Content.ToolResponse(it.name, mapOf("result" to "Alarm set for 07:30")) }))
            val reply = collect(s.stream(answer))
            assertEquals(0, executedByRuntime)
            s.closeAndJoin()
            result("tool_calls", "model=$repo profile=${model.info.profileId} prompt=${q(TOOL_PROMPT)} calls=${calls.map { "${it.name}${it.arguments}" }} chunks=$chunks call_turn_ms=$callMs " +
                "text_beside_calls=${q(said.toString())} executed_by_runtime=$executedByRuntime reply_chunks=${reply.chunks} reply_ms=${reply.totalMs} reply=${q(reply.text)}")
        } finally { model.closeAndJoin() }
    }

    // ---- helpers ----
    private class Collected(val text: String, val chunks: Int, val firstChunkMs: Long, val totalMs: Long)
    private suspend fun collect(flow: Flow<Message>): Collected {
        val sb = StringBuilder(); var n = 0; var first = -1L
        val t0 = SystemClock.elapsedRealtime()
        withTimeout(300_000) { flow.collect { m -> if (n == 0) first = SystemClock.elapsedRealtime() - t0; sb.append(textOf(m)); n++ } }
        return Collected(sb.toString(), n, first, SystemClock.elapsedRealtime() - t0)
    }
    private fun textOf(m: Message) = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
    private class CpuSample(val cpuMs: Long, val wallMs: Long)
    private suspend fun cpuOverMs(ms: Long): CpuSample { val c0 = processCpuMs(); val t0 = SystemClock.elapsedRealtime(); delay(ms); return CpuSample(processCpuMs() - c0, SystemClock.elapsedRealtime() - t0) }
    private fun processCpuMs(): Long {
        val stat = File("/proc/self/stat").readText()
        val f = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
        return (f[11].toLong() + f[12].toLong()) * 1000 / Os.sysconf(OsConstants._SC_CLK_TCK)
    }
    private fun q(s: String) = "\"" + s.take(100).replace("\n", " ").replace("\"", "'") + "\""
    private fun result(check: String, values: String) {
        Log.i(TAG, "RESULT check=$check device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} litertlm=${BuildConfig.LITERTLM_VERSION} $values")
    }

    private companion object {
        const val TAG = "hfmodels-a2"
        const val SYSTEM = "You are a helpful assistant."
        const val PROMPT = "What is 17 + 25? Answer briefly."
        const val LONG_PROMPT = "Count from 1 to 400, separated by commas, with no other text."
        const val FG_REPO = "litert-community/functiongemma-270m-ft-mobile-actions"
        const val G4_REPO = "litert-community/gemma-4-E2B-it-litert-lm"
        const val FG_DESCRIPTOR = "litert-community__functiongemma-270m-ft-mobile-actions.hfmodels.json"
        const val TOOL_PROMPT = "Set an alarm for 7:30 in the morning."
        const val ALARM_TOOL = """{"name": "set_alarm", "description": "Sets an alarm on this phone.", "parameters": {"type": "object", "properties": {"hour": {"type": "integer", "description": "Hour in 24-hour time (0-23)."}, "minute": {"type": "integer", "description": "Minute (0-59)."}}, "required": ["hour", "minute"]}}"""
        val REF = ModelRef("litert-community/Qwen2.5-1.5B-Instruct", revision = "19edb84c0000000000000000000000000000000000".take(40).padEnd(40, '0'))
        // The q8 bundle as published (sha256 / bytes from the Hub's LFS metadata, 2026-09-05). The commit is a placeholder for
        // this offline test; A3 generates the real descriptor with the real commit.
        val DESCRIPTOR = """
        {"schema_version": 1, "model_id": "litert-community/Qwen2.5-1.5B-Instruct", "tasks": ["chat"], "default_variant": "q8",
         "license": {"id": "apache-2.0", "url": "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct"},
         "variants": [{"id": "q8", "runtime": "litert_lm", "handler": {"id": "litertlm.conversation", "abi": 1},
           "runtime_range": {"min_inclusive": "0.16.0", "max_exclusive": "0.18.0"}, "inputs": ["text"],
           "files": [{"id": "weights", "role": "model", "path": "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", "bytes": 1597931520,
                      "sha256": "faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9"}],
           "default_profile": "cpu",
           "profiles": [
             {"id": "cpu", "priority": 50, "files": ["weights"], "enabled_inputs": ["text"], "components": {"language": "cpu"}, "context_tokens": 4096},
             {"id": "gpu", "priority": 100, "files": ["weights"], "enabled_inputs": ["text"], "components": {"language": "gpu"}, "fallback_profiles": ["cpu"], "context_tokens": 4096}],
           "handler_config": {"metadata_source": "publisher_declared", "context_tokens": 4096}}]}
        """.trimIndent()
    }
}
