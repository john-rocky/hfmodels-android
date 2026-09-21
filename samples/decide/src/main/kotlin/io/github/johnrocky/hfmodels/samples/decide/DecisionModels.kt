package io.github.johnrocky.hfmodels.samples.decide

import android.content.Context
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.CredentialProvider
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.decide.TypedDecisions
import io.github.johnrocky.hfmodels.litert.EncoderDecisions

/**
 * The decision model behind the three screens: `litert-community/laya-LiteRT`, loaded by id. The
 * first load downloads the variant's graph (0.6 to 1.7 GB), tokenizer and config files into the
 * app's private storage and verifies them; later loads are offline. A copy pushed to the app's
 * external files dir (`adb push <file> /sdcard/Android/data/<applicationId>/files/`) is imported
 * instead of downloaded.
 */
class DecisionModels(private val context: Context) {
    val models = HfModels(context.applicationContext)
    var model: TypedDecisions? = null
        private set

    /** The repo's variants, in the order the spinner shows them: multilingual first (Japanese and English states), English second. */
    val variants = listOf("ml_s256_fp32", "en_s256_fp32", "en_s512_fp32", "ml_s512_fp32", "ml_s256_wfp16", "en_s512_wfp16")

    /** A Hub token for a private or gated copy of the repo (never stored; the SDK only sends it to the Hub). */
    var token: String? = null

    suspend fun load(variant: String, backend: BackendKind?, onEvent: (LoadEvent) -> Unit): TypedDecisions {
        val m = models.fromPretrained(
            ModelRef(REPO, variant = variant),
            EncoderDecisions,
            LoadOptions(
                backendPolicy = backend?.let { BackendPolicy.Require(it) } ?: BackendPolicy.Auto,
                credentials = token?.let { t -> CredentialProvider { t } },
            ),
            onEvent,
        )
        model = m
        return m
    }

    suspend fun release() {
        model?.closeAndJoin()
        model = null
    }

    companion object {
        const val REPO = "litert-community/laya-LiteRT"
    }
}
