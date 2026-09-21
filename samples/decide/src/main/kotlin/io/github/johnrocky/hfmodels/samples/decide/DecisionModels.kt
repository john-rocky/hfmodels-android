package io.github.johnrocky.hfmodels.samples.decide

import android.content.Context
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.decide.TypedDecisions
import io.github.johnrocky.hfmodels.litert.EncoderDecisions

/**
 * The decision model behind the three screens. Development state of the model files: the
 * tokenizer and config come from the publisher's repo at a pinned commit; the converted graphs are
 * not published yet, so they are side-loaded (`adb push <file> /sdcard/Android/data/<applicationId>/files/`)
 * and described by the development descriptor shipped as an asset. When the graphs are published,
 * this becomes `ModelRef("<owner>/<name>")` with no descriptor.
 */
class DecisionModels(private val context: Context) {
    val models = HfModels(context.applicationContext)
    var model: TypedDecisions? = null
        private set

    /** Variant ids of the development descriptor, in the order the spinner shows them. */
    val variants = listOf("ml_s256_fp32", "en_s256_fp32", "en_s512_fp32", "en_s512_wfp16", "en_s256_wfp16")

    suspend fun load(variant: String, backend: BackendKind?, onEvent: (LoadEvent) -> Unit): TypedDecisions {
        val descriptor = context.assets.open("convaiinnovations__laya-litert-dev.hfmodels.json").bufferedReader().use { it.readText() }
        val m = models.fromPretrained(
            ModelRef("convaiinnovations/laya", revision = "1c5edc17a7acd8701df6fc341c0d179f1c62c982", variant = variant),
            EncoderDecisions,
            LoadOptions(backendPolicy = backend?.let { BackendPolicy.Require(it) } ?: BackendPolicy.Auto, descriptorJson = descriptor),
            onEvent,
        )
        model = m
        return m
    }

    suspend fun release() {
        model?.closeAndJoin()
        model = null
    }
}
