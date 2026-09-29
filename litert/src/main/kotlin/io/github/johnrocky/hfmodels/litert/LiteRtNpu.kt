package io.github.johnrocky.hfmodels.litert

import android.content.Context
import android.os.Build
import com.google.ai.edge.litert.NpuCompatibilityChecker
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import java.io.File

/**
 * The Qualcomm HTP (Hexagon NPU) path of LiteRT 2.2.0, compiled on the phone (JIT) and cached in the
 * app's cache dir. Qualcomm's license lets the vendor libraries ship only inside an application, so
 * they are not in this AAR: the app packages them (docs/api.md, "NPU"), extracted to its native
 * library dir (`packaging { jniLibs { useLegacyPackaging = true } }`). This object checks, before a
 * compile, what LiteRT needs on this phone and names what is missing.
 */
internal object LiteRtNpu {
    /** The SoCs LiteRT 2.2.0 lists for Qualcomm (`NpuCompatibilityChecker.Qualcomm`) and their Hexagon version (its runtime modules' device groups). */
    private val HEXAGON = mapOf("SM8550" to "V73", "SM8650" to "V75", "SM8750" to "V79", "SM8850" to "V81")

    /** LiteRT's dispatch library and JIT compiler plugin (LiteRT GitHub release), then QAIRT's; libQnnHtpPrepare, libQnnIr and libQnnSaver are the JIT compiler's. */
    private val COMMON = listOf(
        "libLiteRtDispatch_Qualcomm.so", "libLiteRtCompilerPlugin_Qualcomm.so",
        "libQnnHtp.so", "libQnnSystem.so", "libQnnHtpPrepare.so", "libQnnIr.so", "libQnnSaver.so",
    )

    /** The Hexagon version of this phone's SoC, or null when LiteRT 2.2.0 has no Qualcomm NPU path for it. */
    fun hexagon(): String? = HEXAGON[Build.SOC_MODEL]?.takeIf { NpuCompatibilityChecker.Qualcomm.isDeviceSupported() }

    /** Every library the NPU path loads on a SoC with this Hexagon version. */
    fun libraries(hexagon: String): List<String> = COMMON + listOf("libQnnHtp${hexagon}Stub.so", "libQnnHtp${hexagon}Skel.so")

    /** True when the app has every library this phone's NPU path needs, extracted to its native library dir. */
    fun ready(context: Context): Boolean {
        val hexagon = hexagon() ?: return false
        val dir = File(context.applicationInfo.nativeLibraryDir)
        return libraries(hexagon).all { File(dir, it).isFile }
    }

    /** Throws the fix when the NPU cannot run here: an unsupported SoC, or libraries the app does not package. */
    fun requireReady(context: Context?) {
        if (context == null) throw ModelException(ErrorCode.UNSUPPORTED_CONFIGURATION, "the NPU needs an application context (HfModels(context)); none in this process")
        val hexagon = hexagon() ?: throw ModelException(
            ErrorCode.UNSUPPORTED_CONFIGURATION,
            "no Qualcomm NPU path in LiteRT ${BuildConfig.LITERT_VERSION} for this SoC (${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}); it lists ${HEXAGON.keys.joinToString()}",
            details = mapOf("soc" to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"),
        )
        val dir = File(context.applicationInfo.nativeLibraryDir)
        val missing = libraries(hexagon).filterNot { File(dir, it).isFile }
        if (missing.isNotEmpty()) throw ModelException(
            ErrorCode.NATIVE_MODULE_MISSING,
            "the NPU needs ${missing.joinToString()} in ${dir.path}: package the Qualcomm runtime for Hexagon $hexagon in the app with jniLibs.useLegacyPackaging = true (docs/api.md, NPU)",
            details = mapOf("missing" to missing.joinToString(), "hexagon" to hexagon),
        )
    }
}
