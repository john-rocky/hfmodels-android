package io.github.johnrocky.hfmodels.decide

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException

/**
 * One typed question about a state. The three types and their JSON form (`type`, `instructions`,
 * `criteria`) are the ones a `/v1/systemone`-style request uses, so a request written for a
 * server can be handed to an on-device model unchanged.
 */
sealed class Question {
    abstract val instructions: String

    /** Pick exactly one of the keys. A value describes the key; null or "" means "no description". Order matters and is kept. */
    data class Choice(override val instructions: String, val criteria: Map<String, String?>) : Question() {
        init {
            if (criteria.size < 2) throw ModelException(ErrorCode.INVALID_INPUT, "a choice question needs at least 2 criteria, got ${criteria.size}")
        }
        constructor(instructions: String, vararg keys: String) : this(instructions, keys.associateWith { null })
    }

    /** An ordered scale: index 0 is the lowest level. The answer is the expected level, a real number in [0, size-1]. */
    data class Score(override val instructions: String, val criteria: List<String>) : Question() {
        init {
            if (criteria.size < 2) throw ModelException(ErrorCode.INVALID_INPUT, "a score question needs at least 2 levels, got ${criteria.size}")
        }
    }

    /** Does the statement hold? `criteria` may describe the `false` and `true` sides. The answer is the probability that it holds. */
    data class Noul(override val instructions: String, val criteria: Map<String, String>? = null) : Question()

    /** The JSON object form, as an ordered map (`Json.dumps` renders it). */
    fun toMap(): Map<String, Any?> = LinkedHashMap<String, Any?>().also { o ->
        when (this) {
            is Choice -> { o["type"] = "choice"; o["instructions"] = instructions; o["criteria"] = LinkedHashMap(criteria) }
            is Score -> { o["type"] = "score"; o["instructions"] = instructions; o["criteria"] = ArrayList(criteria) }
            is Noul -> { o["type"] = "noul"; o["instructions"] = instructions; criteria?.let { o["criteria"] = LinkedHashMap(it) } }
        }
    }

    companion object {
        /** One question from its JSON object (`{"type": "choice" | "score" | "noul", "instructions": …, "criteria": …}`). */
        fun fromMap(o: Map<*, *>): Question {
            val type = o["type"] as? String ?: throw ModelException(ErrorCode.INVALID_INPUT, "question has no string 'type'")
            val instructions = (o["instructions"] as? String)?.takeIf { it.isNotEmpty() } ?: throw ModelException(ErrorCode.INVALID_INPUT, "question has no 'instructions'")
            return when (type) {
                "choice" -> {
                    // An object (key -> description) or, as the reference accepts, a list of keys without descriptions.
                    val map = LinkedHashMap<String, String?>()
                    when (val c = o["criteria"]) {
                        is Map<*, *> -> for ((k, v) in c) map[k.toString()] = when (v) { null -> null; is String -> v; else -> Json.dumps(v) }
                        is List<*> -> for (k in c) map[k.toString()] = null
                        else -> throw ModelException(ErrorCode.INVALID_INPUT, "choice question needs 'criteria' (an object key -> description, or a list of keys)")
                    }
                    Choice(instructions, map)
                }
                "score" -> {
                    val a = o["criteria"] as? List<*> ?: throw ModelException(ErrorCode.INVALID_INPUT, "score question needs an array 'criteria'")
                    Score(instructions, a.map { if (it is String) it else Json.dumps(it) })
                }
                "noul" -> {
                    val c = o["criteria"] as? Map<*, *>
                    Noul(instructions, c?.let { obj -> LinkedHashMap<String, String>().also { m -> for ((k, v) in obj) if (v != null) m[k.toString()] = if (v is String) v else Json.dumps(v) } })
                }
                else -> throw ModelException(ErrorCode.INVALID_INPUT, "unknown question type '$type' (choice | score | noul)")
            }
        }

        /** A `questions` object (question id -> question), order kept. */
        fun parseAll(json: String): Map<String, Question> {
            val o = Json.parseObject(json)
            val out = LinkedHashMap<String, Question>()
            for ((k, v) in o) out[k] = fromMap(v as? Map<*, *> ?: throw ModelException(ErrorCode.INVALID_INPUT, "question '$k' is not an object"))
            if (out.isEmpty()) throw ModelException(ErrorCode.INVALID_INPUT, "no questions")
            return out
        }
    }
}
