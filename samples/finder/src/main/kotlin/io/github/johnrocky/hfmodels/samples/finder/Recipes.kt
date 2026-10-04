package io.github.johnrocky.hfmodels.samples.finder

import io.github.johnrocky.hfmodels.decide.Json

/**
 * One made-up recipe of the list (`src/main/assets/recipes.json`): a common dish name, the meal it is for, its diet
 * (`vegan`, `vegetarian`, `meat` or `fish`), minutes from start to table, which of nuts / dairy / gluten it contains,
 * and how spicy it is (`mild`, `medium` or `hot`).
 */
data class Recipe(
    val id: String,
    val name: String,
    val meal: String,
    val diet: String,
    val minutes: Int,
    val contains: List<String>,
    val spice: String,
)

object Recipes {
    const val ASSET = "recipes.json"
    val MEALS = listOf("breakfast", "lunch", "dinner", "dessert")
    val DIETS = listOf("vegan", "vegetarian", "meat", "fish")
    val INGREDIENTS = listOf("nuts", "dairy", "gluten")
    val SPICES = listOf("mild", "medium", "hot")

    /** The asset's array of recipe objects, in the order the list shows them. */
    fun parse(text: String): List<Recipe> = (Json.parse(text) as List<*>).map { item ->
        val o = item as Map<*, *>
        Recipe(
            id = o["id"] as String,
            name = o["name"] as String,
            meal = o["meal"] as String,
            diet = o["diet"] as String,
            minutes = (o["minutes"] as Long).toInt(),
            contains = (o["contains"] as List<*>).map { it as String },
            spice = o["spice"] as String,
        )
    }
}

/** The five filters, one per question; each is unset ([Facet.unset]) or one of the question's other keys. */
enum class Facet(val id: String, val label: String, val unset: String) {
    MEAL("meal", "Meal", "any"),
    DIET("diet", "Diet", "any"),
    TIME("time", "Time", "any"),
    WITHOUT("exclude", "Without", "nothing"),
    SPICE("spice", "Spice", "any"),
}

/**
 * The filters a sentence set: per question id (meal, diet, time, exclude, spice) the answer's key. What each one keeps:
 * - meal: the recipes for that meal;
 * - diet: `vegetarian` keeps the vegetarian and the vegan recipes, `vegan` the vegan ones only;
 * - time: `under_15`, `under_30`, `under_60` keep the recipes ready in at most 15, 30 or 60 minutes;
 * - exclude: the recipes that do not contain that ingredient (nuts, dairy or gluten);
 * - spice: the recipes of that level (mild, medium or hot).
 * An unset filter (`any`, or `nothing` for exclude) keeps every recipe.
 */
data class Filters(val keys: Map<String, String> = UNSET) {
    operator fun get(f: Facet): String = keys[f.id] ?: f.unset
    fun isSet(f: Facet): Boolean = get(f) != f.unset
    fun with(f: Facet, key: String): Filters = Filters(keys + (f.id to key))
    fun cleared(f: Facet): Filters = with(f, f.unset)
    val setCount: Int get() = Facet.values().count { isSet(it) }

    fun keeps(r: Recipe): Boolean {
        val meal = get(Facet.MEAL)
        if (meal != Facet.MEAL.unset && r.meal != meal) return false
        when (get(Facet.DIET)) {
            "vegetarian" -> if (r.diet != "vegetarian" && r.diet != "vegan") return false
            "vegan" -> if (r.diet != "vegan") return false
        }
        val limit = MINUTES[get(Facet.TIME)]
        if (limit != null && r.minutes > limit) return false
        val without = get(Facet.WITHOUT)
        if (without != Facet.WITHOUT.unset && without in r.contains) return false
        val spice = get(Facet.SPICE)
        if (spice != Facet.SPICE.unset && r.spice != spice) return false
        return true
    }

    fun keep(recipes: List<Recipe>): List<Recipe> = recipes.filter { keeps(it) }

    companion object {
        val UNSET: Map<String, String> = Facet.values().associate { it.id to it.unset }
        /** The time filter's keys and their limits in minutes (inclusive). */
        val MINUTES = mapOf("under_15" to 15, "under_30" to 30, "under_60" to 60)

        fun of(meal: String = "any", diet: String = "any", time: String = "any", exclude: String = "nothing", spice: String = "any") =
            Filters(linkedMapOf("meal" to meal, "diet" to diet, "time" to time, "exclude" to exclude, "spice" to spice))
    }
}

/** The text of a card's attribute line, one part per facet, in the card's order. */
object RecipeLine {
    /** A part of the line and the facet whose set filter it satisfies (null: shown plain). */
    class Part(val text: String, val facet: Facet?)

    /**
     * `dinner · vegan · 40 min · medium · no nuts`: the meal, the diet, the minutes, the spice; then, with the Without
     * filter set, `no <ingredient>`, else `with` what the recipe contains (nothing when it contains none of the three).
     * A part is colored when its filter is set: every recipe on the list satisfies the set filters, so the colored parts
     * say why it is there.
     */
    fun parts(r: Recipe, f: Filters): List<Part> {
        fun on(facet: Facet) = if (f.isSet(facet)) facet else null
        val parts = arrayListOf(
            Part(r.meal, on(Facet.MEAL)),
            Part(r.diet, on(Facet.DIET)),
            Part("${r.minutes} min", on(Facet.TIME)),
            Part(r.spice, on(Facet.SPICE)),
        )
        when {
            f.isSet(Facet.WITHOUT) -> parts += Part("no ${f[Facet.WITHOUT]}", Facet.WITHOUT)
            r.contains.isNotEmpty() -> parts += Part("with ${r.contains.joinToString(", ")}", null)
        }
        return parts
    }

    const val SEPARATOR = " · "

    fun text(r: Recipe, f: Filters): String = parts(r, f).joinToString(SEPARATOR) { it.text }
}
