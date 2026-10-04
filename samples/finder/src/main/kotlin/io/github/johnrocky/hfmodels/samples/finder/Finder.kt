package io.github.johnrocky.hfmodels.samples.finder

import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.Question
import io.github.johnrocky.hfmodels.decide.TypedDecisions

/**
 * The five questions every sentence gets, and what their answers become. They are the F1 questions of the round-18
 * sieve that picked this feature (`questions_v1.json`, `"f1"`), word for word: the unit test compares them with that
 * file. The `gliner2_decide` family asks each question in its own forward (one gliner2 task with the instructions as
 * its prompt and the descriptions as its labels), so the descriptions are what the model reads; the keys come back.
 */
object Finder {
    val MEAL = Question.Choice(
        "Which meal is the request for?",
        linkedMapOf("any" to "no meal named", "breakfast" to "breakfast", "lunch" to "lunch", "dinner" to "dinner", "dessert" to "dessert"),
    )
    val DIET = Question.Choice(
        "Which diet must the recipes follow?",
        linkedMapOf("any" to "no diet named", "vegetarian" to "vegetarian", "vegan" to "vegan"),
    )
    val TIME = Question.Choice(
        "How much cooking time is allowed?",
        linkedMapOf("any" to "no time limit named", "under_15" to "under 15 minutes", "under_30" to "under 30 minutes", "under_60" to "under an hour"),
    )
    val EXCLUDE = Question.Choice(
        "Which ingredient must be left out?",
        linkedMapOf("nothing" to "nothing to leave out", "nuts" to "no nuts", "dairy" to "no dairy", "gluten" to "no gluten"),
    )
    val SPICE = Question.Score("How spicy should the food be?", listOf("not mentioned", "mild", "medium", "hot"))

    /** The spice levels as filter keys, by level index (the sieve's `f1_spice_keys`). */
    val SPICE_KEYS = listOf("any", "mild", "medium", "hot")

    /** Question id -> question, in the order the sieve asked them. The ids are the filters' ([Facet.id]). */
    val QUESTIONS: Map<String, Question> = linkedMapOf("meal" to MEAL, "diet" to DIET, "time" to TIME, "exclude" to EXCLUDE, "spice" to SPICE)

    /** A sentence of the recording mode or of Try, with the filters the sieve's gold gives it (`f1_filter.jsonl`). */
    class Sentence(val id: String, val text: String, val expected: Filters)

    /**
     * The recording mode's two sentences: f1_33 sets all five filters, f1_14 three. Both are sentences the sieve's Mac
     * host answered right on all five questions.
     */
    val SCRIPT = listOf(
        Sentence("f1_33", "Medium spicy vegan dinner without nuts, ready within an hour.", Filters.of(meal = "dinner", diet = "vegan", time = "under_60", exclude = "nuts", spice = "medium")),
        Sentence("f1_14", "A vegan dessert without nuts.", Filters.of(meal = "dessert", diet = "vegan", exclude = "nuts")),
    )

    /** Try: three more sentences the Mac host answered right on all five questions, one per meal of the day. */
    val EXAMPLES = listOf(
        Sentence("f1_34", "Dairy-free vegetarian breakfast in under 10 minutes.", Filters.of(meal = "breakfast", diet = "vegetarian", time = "under_15", exclude = "dairy")),
        Sentence("f1_32", "A vegetarian lunch I can make in 15 minutes, no nuts.", Filters.of(meal = "lunch", diet = "vegetarian", time = "under_15", exclude = "nuts")),
        Sentence("f1_16", "A really hot and spicy vegetarian dinner.", Filters.of(meal = "dinner", diet = "vegetarian", spice = "hot")),
    )

    /** One sentence's five answers. */
    class Result(
        val text: String,
        /** Per question id, the answer's key: the filters this sentence sets. */
        val filters: Filters,
        /** Per question id: per key, the probability (spice under its filter keys, [SPICE_KEYS]). */
        val probabilities: Map<String, Map<String, Double>>,
        /** Per question id: the SDK's milliseconds for that question's forward (DecisionTiming.questionMs). */
        val questionMs: Map<String, Double>,
        /** The SDK's wall clock for the whole call: the sentence tokenized once and the five forwards (DecisionTiming.totalMs). */
        val totalMs: Double,
        /** The sentence's tokens. */
        val stateTokens: Int,
    )

    /** One decide() with the five questions. Blocks the calling thread for the forwards: call it off the main thread. */
    suspend fun ask(model: TypedDecisions, text: String): Result {
        val d = model.decide(text, QUESTIONS)
        val keys = LinkedHashMap<String, String>()
        val probabilities = LinkedHashMap<String, Map<String, Double>>()
        for ((id, a) in d.answers) {
            val p = probabilities(a)
            probabilities[id] = p
            keys[id] = key(a, p)
        }
        val ms = LinkedHashMap<String, Double>()
        QUESTIONS.keys.forEachIndexed { i, id -> ms[id] = d.timing.questionMs[i] }
        return Result(text, Filters(keys), probabilities, ms, d.timing.totalMs, d.stateTokens)
    }

    /** A choice's probabilities as they come; the spice score's per level, under [SPICE_KEYS]. */
    fun probabilities(a: Answer): Map<String, Double> = when (a) {
        is Answer.Choice -> LinkedHashMap(a.probabilities)
        is Answer.Score -> LinkedHashMap<String, Double>().also { m -> SPICE_KEYS.forEachIndexed { i, k -> m[k] = a.probabilities.getValue(i.toString()) } }
        is Answer.Noul -> throw IllegalArgumentException("no yes / no question here")
    }

    /**
     * The filter key an answer stands for: a choice's choice; for the spice score, the level with the highest probability
     * (the first one on a tie), as the sieve read it. The score's expected level (Answer.Score.score) is not used: level 0
     * means "not mentioned", so an average across the levels is not a level.
     */
    fun key(a: Answer, p: Map<String, Double> = probabilities(a)): String = when (a) {
        is Answer.Choice -> a.choice
        else -> {
            var best = SPICE_KEYS[0]
            for (k in SPICE_KEYS) if (p.getValue(k) > p.getValue(best)) best = k
            best
        }
    }

    /** Every value a filter can take, unset first: the question's keys (spice: [SPICE_KEYS]). A chip's menu offers them in this order. */
    fun keys(f: Facet): List<String> = when (val q = QUESTIONS.getValue(f.id)) {
        is Question.Choice -> q.criteria.keys.toList()
        else -> SPICE_KEYS
    }

    /** A chip's value: the time filter by the words the model read (`under an hour`), the others by their key. */
    fun chipText(f: Facet, key: String): String = if (f == Facet.TIME) TIME.criteria[key]?.takeIf { key != f.unset } ?: key else key
}
