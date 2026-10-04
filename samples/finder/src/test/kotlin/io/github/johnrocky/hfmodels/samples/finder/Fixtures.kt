package io.github.johnrocky.hfmodels.samples.finder

import io.github.johnrocky.hfmodels.decide.Json
import java.io.File
import java.security.MessageDigest

/** The module's files as the JVM tests read them (the working directory is the module's, or a parent of it). */
object Fixtures {
    fun file(path: String): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { sequenceOf(File(it, path), File(it, "samples/finder/$path")) }
        .first { it.isFile }

    val recipes: List<Recipe> by lazy { Recipes.parse(file("src/main/assets/${Recipes.ASSET}").readText()) }

    /** The round-18 sieve's F1 fixture (40 sentences and their gold), the device check's asset. */
    val sieve: Map<String, Map<String, Any?>> by lazy { jsonl("src/androidTest/assets/f1_filter.jsonl") }

    /** The Mac host's answers to the same 40 (GLiNER2.5-Decide s128_wfp16, one question per forward), the device check's asset. */
    val mac: Map<String, Map<String, Any?>> by lazy { jsonl("src/androidTest/assets/gliner_f1_v1.jsonl") }

    @Suppress("UNCHECKED_CAST")
    fun gold(id: String): Filters = Filters(sieve.getValue(id)["gold"] as Map<String, String>)

    @Suppress("UNCHECKED_CAST")
    fun macAnswers(id: String): Filters = Filters(mac.getValue(id)["answers"] as Map<String, String>)

    private fun jsonl(path: String): Map<String, Map<String, Any?>> = file(path).readLines().filter { it.isNotBlank() }
        .map { Json.parseObject(it) }.associateBy { it["id"] as String }

    fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}
