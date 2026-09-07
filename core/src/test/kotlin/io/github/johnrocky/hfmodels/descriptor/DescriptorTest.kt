package io.github.johnrocky.hfmodels.descriptor

import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.InputKind
import io.github.johnrocky.hfmodels.ModelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

object Fixtures {
    const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    fun chat(modelId: String = "org/model", extraProfile: String = "", cpuFallback: String = "[]", handlerConfig: String = """{"metadata_source": "publisher_declared"}""", runtimeMax: String? = "0.17.0", fileSha: String = SHA, bytes: Long = 1048576, path: String = "model.litertlm"): String = """
    {
      "schema_version": 1,
      "model_id": "$modelId",
      "tasks": ["chat"],
      "default_variant": "q8",
      "license": {"id": "apache-2.0", "url": "https://huggingface.co/$modelId/blob/main/LICENSE"},
      "variants": [{
        "id": "q8",
        "runtime": "litert_lm",
        "handler": {"id": "litertlm.conversation", "abi": 1},
        "runtime_range": {"min_inclusive": "0.16.0"${if (runtimeMax != null) ", \"max_exclusive\": \"$runtimeMax\"" else ""}},
        "inputs": ["text"],
        "files": [{"id": "weights", "role": "model", "path": "$path", "bytes": $bytes, "sha256": "$fileSha"}],
        "default_profile": "cpu",
        "profiles": [
          {"id": "cpu", "priority": 50, "files": ["weights"], "enabled_inputs": ["text"], "components": {"language": "cpu"},
           "requirements": {"min_android_api": 31, "abis": ["arm64-v8a"]}, "fallback_profiles": $cpuFallback, "default_selectable": true,
           "verification": [{"level": "MAINTAINER_TESTED", "device": "Pixel 8a", "os_build": "CP1A.260505.005", "runtime": "litert_lm 0.16.1", "result": "PASS", "date": "2026-09-07"}]},
          {"id": "gpu", "priority": 100, "files": ["weights"], "enabled_inputs": ["text"], "components": {"language": "gpu"},
           "requirements": {"min_android_api": 31, "abis": ["arm64-v8a"]}, "fallback_profiles": ["cpu"], "default_selectable": true}
          $extraProfile
        ],
        "handler_config": $handlerConfig
      }]
    }
    """.trimIndent()
}

class DescriptorTest {
    @Test fun parsesTheChatFixture() {
        val d = Descriptor.parse(Fixtures.chat())
        assertEquals("org/model", d.modelId)
        assertEquals(listOf("chat"), d.tasks)
        val v = d.variant(null)!!
        assertEquals("q8", v.id)
        assertEquals("litertlm.conversation", v.handler.id)
        assertEquals(setOf(InputKind.TEXT), v.inputs)
        assertEquals(BackendKind.GPU, v.profile("gpu")!!.primary)
        assertEquals(listOf("cpu"), v.profile("gpu")!!.fallbackProfiles)
        assertEquals("MAINTAINER_TESTED", v.profile("cpu")!!.verification.single().level)
        assertEquals("publisher_declared", v.handlerConfig.getString("metadata_source"))
        assertTrue(v.runtimeRange.contains("0.16.1"))
        assertFalse(v.runtimeRange.contains("0.17.0"))
        assertFalse(v.runtimeRange.contains("0.15.9"))
    }

    private fun expect(code: ErrorCode, text: String, fragment: String? = null) {
        try { Descriptor.parse(text); fail("expected $code") } catch (e: ModelException) {
            assertEquals(e.reason, code, e.code)
            if (fragment != null) assertTrue("'${e.reason}' should mention '$fragment'", e.reason.contains(fragment))
        }
    }

    @Test fun unknownMajorSchemaIsRejected() = expect(ErrorCode.UNSUPPORTED_SCHEMA, Fixtures.chat().replace("\"schema_version\": 1", "\"schema_version\": 2"))
    @Test fun notJsonIsInvalid() = expect(ErrorCode.MANIFEST_INVALID, "not json")
    @Test fun modelIdMustMatchTarget() {
        try { Descriptor.parse(Fixtures.chat("other/repo"), modelIdExpected = "org/model"); fail() } catch (e: ModelException) { assertEquals(ErrorCode.MANIFEST_INVALID, e.code) }
    }
    @Test fun badShaIsInvalid() = expect(ErrorCode.MANIFEST_INVALID, Fixtures.chat(fileSha = "ABC"), "sha256")
    @Test fun negativeBytesIsInvalid() = expect(ErrorCode.MANIFEST_INVALID, Fixtures.chat(bytes = -1), "bytes")
    @Test fun absoluteOrDotDotPathIsInvalid() {
        expect(ErrorCode.MANIFEST_INVALID, Fixtures.chat(path = "/etc/passwd"), "path")
        expect(ErrorCode.MANIFEST_INVALID, Fixtures.chat(path = "../x.litertlm"), "path")
    }
    @Test fun unknownDefaultVariantIsInvalid() = expect(ErrorCode.MANIFEST_INVALID, Fixtures.chat().replace("\"default_variant\": \"q8\"", "\"default_variant\": \"nope\""), "default_variant")
    @Test fun unknownFileIdInProfileIsInvalid() = expect(ErrorCode.MANIFEST_INVALID, Fixtures.chat().replace("\"files\": [\"weights\"], \"enabled_inputs\": [\"text\"], \"components\": {\"language\": \"cpu\"}", "\"files\": [\"ghost\"], \"enabled_inputs\": [\"text\"], \"components\": {\"language\": \"cpu\"}"), "unknown file id")
    @Test fun fallbackCycleIsInvalid() = expect(ErrorCode.MANIFEST_INVALID, Fixtures.chat(cpuFallback = "[\"gpu\"]"), "cycle")
    @Test fun enabledInputsBeyondVariantIsInvalid() = expect(ErrorCode.MANIFEST_INVALID, Fixtures.chat().replace("\"enabled_inputs\": [\"text\"], \"components\": {\"language\": \"gpu\"}", "\"enabled_inputs\": [\"text\", \"image\"], \"components\": {\"language\": \"gpu\"}"), "enabled_inputs")
    @Test fun unknownRuntimeOrHandlerAbiIsInvalid() {
        expect(ErrorCode.MANIFEST_INVALID, Fixtures.chat().replace("\"runtime\": \"litert_lm\"", "\"runtime\": \"onnx\""), "runtime")
        expect(ErrorCode.MANIFEST_INVALID, Fixtures.chat().replace("\"abi\": 1", "\"abi\": 0"), "abi")
    }
    @Test fun otherLicenseNeedsUrl() = expect(ErrorCode.MANIFEST_INVALID, Fixtures.chat().replace("\"license\": {\"id\": \"apache-2.0\", \"url\": \"https://huggingface.co/org/model/blob/main/LICENSE\"}", "\"license\": {\"id\": \"other\"}"), "license")

    @Test fun versionCompare() {
        assertTrue(RuntimeRange.compareVersions("0.16.1", "0.16.0") > 0)
        assertTrue(RuntimeRange.compareVersions("0.17.0-alpha1", "0.17.0") < 0)
        assertTrue(RuntimeRange.compareVersions("2.2.0", "2.10.0") < 0)
        assertEquals(0, RuntimeRange.compareVersions("0.16", "0.16.0"))
        assertTrue(RuntimeRange("0.16.0", null).contains("9.9.9"))
    }
}
