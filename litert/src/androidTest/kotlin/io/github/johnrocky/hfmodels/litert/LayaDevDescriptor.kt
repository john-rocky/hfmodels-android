package io.github.johnrocky.hfmodels.litert

import androidx.test.platform.app.InstrumentationRegistry

/** The development descriptor (`catalog/dev/…hfmodels.json`, copied into the test APK's assets by the build). */
object LayaDevDescriptor {
    fun json(variantId: String): String {
        val text = InstrumentationRegistry.getInstrumentation().context.assets.open("convaiinnovations__laya-litert-dev.hfmodels.json").bufferedReader().use { it.readText() }
        require(text.contains("\"$variantId\"")) { "variant $variantId is not in the development descriptor" }
        return text
    }
}
