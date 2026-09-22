package io.github.johnrocky.hfmodels.samples.decide.gliner

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The extraction model's files (litert-community/GLiNER2.5-Small-LiteRT at commit cbfa3e14), fetched
 * into the app's private files dir with a sha256 check, or taken from the app's external files dir
 * when they were pushed there (`adb push <file> /sdcard/Android/data/<applicationId>/files/gliner/`).
 * The hashes are the repo's SHA256SUMS. This is what the SDK does for the decision model; the
 * extraction model has no descriptor, so the sample carries the list itself.
 */
object GlinerAssets {
    private const val BASE = "https://huggingface.co/litert-community/GLiNER2.5-Small-LiteRT/resolve/cbfa3e145a8132e524891009ea353a88b3254e29/"

    /** local name -> (repo path, sha256) */
    private val FILES = linkedMapOf(
        "gliner25_small_s128_wfp16.tflite" to ("gliner25_small_s128_wfp16.tflite" to "ea16cb1489fc1086650dbbc5b927600ce8d62937191bb80c816b4c2c5019fc98"),
        "gliner25_small_s256_wfp16.tflite" to ("gliner25_small_s256_wfp16.tflite" to "57fd9ba815f4bc268a4463d605d3c27ebb5d3aad36fd3d6013d02b9dd153efaa"),
        "gliner25_small_s512_wfp16.tflite" to ("gliner25_small_s512_wfp16.tflite" to "824d359f29d8719bfbca960653aa2dd7477368ee8cb0863b53fca04c12fabbff"),
        "word_embeddings_fp32.bin" to ("host_assets/word_embeddings_fp32.bin" to "22519b1bb2a6dd17de463d0ce16891ce0f328804bb7da336d55d4583c36947f5"),
        "tokenizer.json" to ("host_assets/tokenizer.json" to "cbc8ae6037812709c9c26f2a160f8dc48b0440bcb79c8141804259ae2d6adac3"),
        "sparse_decoder_fp32.safetensors" to ("host_assets/sparse_decoder_fp32.safetensors" to "a76467dda515698253e68408db7dd4bd8a5fff5c13612de8038b787d42b9aed2"),
        "graph_contract_s128.json" to ("graph_contract_s128.json" to "a3c324e1586e9939a967f32681bff0549f5b9ad4acbd809ab80a8388811ce3fa"),
        "graph_contract_s256.json" to ("graph_contract_s256.json" to "ec9452120623118e6c5d895c2f1ba6ccfcc80a00950822400b7aa3c327a97106"),
        "graph_contract_s512.json" to ("graph_contract_s512.json" to "6a20a54220a7aff8a1375f07856cc393bf9e4775aea2950227e282da6285f103"),
        "config.json" to ("host_assets/config.json" to "0b7d9e1401ceeb83e992ec66d2f93bff7e5646428f1b4706ec527cf88f53578a"),
    )

    fun dir(filesDir: File): File = File(filesDir, "gliner")

    fun ready(filesDir: File): Boolean = FILES.keys.all { File(dir(filesDir), it).isFile }

    /** Blocking; call off the main thread. Reports each file as it lands. */
    fun ensure(filesDir: File, pushed: File?, progress: (String) -> Unit) {
        val target = dir(filesDir).apply { mkdirs() }
        for ((name, spec) in FILES) {
            val (path, sha) = spec
            val dest = File(target, name)
            if (dest.isFile) continue
            // A pushed copy: in the external files dir itself (the documented push target) or in its gliner/ subdirectory.
            val candidate = pushed?.let { p -> listOf(File(p, name), File(p.parentFile, name)).firstOrNull { it.isFile } }
            if (candidate != null) {
                progress("hashing pushed $name")
                if (sha256(candidate) == sha) { candidate.copyTo(dest, overwrite = true); progress("side-loaded $name"); continue }
                progress("pushed $name does not match the repo's sha256; downloading")
            }
            progress("downloading $name")
            val tmp = File(target, "$name.part")
            val conn = URL(BASE + path).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30_000; conn.readTimeout = 60_000
            conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val got = sha256(tmp)
            if (got != sha) { tmp.delete(); throw IllegalStateException("$name: sha256 $got, expected $sha") }
            if (!tmp.renameTo(dest)) throw IllegalStateException("cannot move $name into place")
            progress("verified $name (${dest.length()} bytes)")
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
