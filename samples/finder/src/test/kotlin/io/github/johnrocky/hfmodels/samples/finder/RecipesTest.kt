package io.github.johnrocky.hfmodels.samples.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipesTest {
    private fun r(meal: String = "dinner", diet: String = "vegan", minutes: Int = 40, contains: List<String> = emptyList(), spice: String = "medium") =
        Recipe("x", "X", meal, diet, minutes, contains, spice)

    @Test fun theAssetIsSixtyWellFormedRecipes() {
        val all = Fixtures.recipes
        assertEquals(60, all.size)
        assertEquals(60, all.map { it.id }.toSet().size)
        assertEquals(60, all.map { it.name }.toSet().size)
        for (x in all) {
            assertTrue(x.id, x.name.isNotBlank())
            assertTrue(x.id, x.meal in Recipes.MEALS)
            assertTrue(x.id, x.diet in Recipes.DIETS)
            assertTrue(x.id, x.spice in Recipes.SPICES)
            assertTrue(x.id, x.minutes in 1..240)
            assertTrue(x.id, x.contains.all { it in Recipes.INGREDIENTS } && x.contains.toSet().size == x.contains.size)
        }
        // Every value of every filter is on the list.
        assertEquals(Recipes.MEALS.toSet(), all.map { it.meal }.toSet())
        assertEquals(Recipes.SPICES.toSet(), all.map { it.spice }.toSet())
        assertEquals(Recipes.INGREDIENTS.toSet(), all.flatMap { it.contains }.toSet())
    }

    @Test fun unsetFiltersKeepEverything() {
        assertEquals(60, Filters().keep(Fixtures.recipes).size)
        assertEquals(0, Filters().setCount)
    }

    @Test fun whatEachFilterKeeps() {
        // meal: equal.
        assertTrue(Filters.of(meal = "dinner").keeps(r(meal = "dinner")))
        assertFalse(Filters.of(meal = "dinner").keeps(r(meal = "lunch")))
        // diet: vegetarian keeps vegan too; vegan keeps vegan only.
        assertTrue(Filters.of(diet = "vegetarian").keeps(r(diet = "vegetarian")))
        assertTrue(Filters.of(diet = "vegetarian").keeps(r(diet = "vegan")))
        assertFalse(Filters.of(diet = "vegetarian").keeps(r(diet = "fish")))
        assertTrue(Filters.of(diet = "vegan").keeps(r(diet = "vegan")))
        assertFalse(Filters.of(diet = "vegan").keeps(r(diet = "vegetarian")))
        // time: at most 15 / 30 / 60 minutes.
        for ((key, limit) in Filters.MINUTES) {
            assertTrue(key, Filters.of(time = key).keeps(r(minutes = limit)))
            assertFalse(key, Filters.of(time = key).keeps(r(minutes = limit + 1)))
        }
        // exclude: without that ingredient.
        assertTrue(Filters.of(exclude = "nuts").keeps(r(contains = listOf("dairy"))))
        assertFalse(Filters.of(exclude = "nuts").keeps(r(contains = listOf("nuts", "dairy"))))
        // spice: equal.
        assertTrue(Filters.of(spice = "medium").keeps(r(spice = "medium")))
        assertFalse(Filters.of(spice = "medium").keeps(r(spice = "hot")))
        // Clearing a filter widens the list.
        val five = Filters.of(meal = "dinner", diet = "vegan", time = "under_60", exclude = "nuts", spice = "medium")
        assertEquals(5, five.setCount)
        assertEquals(4, five.cleared(Facet.WITHOUT).setCount)
        assertTrue(five.cleared(Facet.WITHOUT).keep(Fixtures.recipes).size > five.keep(Fixtures.recipes).size)
    }

    @Test fun aChipMenuOffersEveryValueAndAPickSetsThatFilterAlone() {
        // The menu: every value of the filter, unset first, the question's own keys.
        assertEquals(listOf("any", "breakfast", "lunch", "dinner", "dessert"), Finder.keys(Facet.MEAL))
        assertEquals(listOf("any", "vegetarian", "vegan"), Finder.keys(Facet.DIET))
        assertEquals(listOf("any", "under 15 minutes", "under 30 minutes", "under an hour"), Finder.keys(Facet.TIME).map { Finder.chipText(Facet.TIME, it) })
        assertEquals(listOf("nothing", "nuts", "dairy", "gluten"), Finder.keys(Facet.WITHOUT))
        assertEquals(listOf("any", "mild", "medium", "hot"), Finder.keys(Facet.SPICE))
        for (f in Facet.values()) assertEquals(f.id, f.unset, Finder.keys(f).first())
        // A pick on an unset filter sets it by hand; any other filter stays as it was.
        val all = Fixtures.recipes
        val dinner = Filters().with(Facet.MEAL, "dinner")
        assertEquals(1, dinner.setCount)
        assertEquals(20, dinner.keep(all).size)
        // A pick on a set filter replaces its value: the sentence's wrong chip fixed by hand.
        val five = Filters.of(meal = "dinner", diet = "vegan", time = "under_60", exclude = "nuts", spice = "medium")
        val hot = five.with(Facet.SPICE, "hot")
        assertEquals("hot", hot[Facet.SPICE])
        for (f in Facet.values().filter { it != Facet.SPICE }) assertEquals(f.id, five[f], hot[f])
        assertEquals(listOf("r35"), hot.cleared(Facet.WITHOUT).keep(all).map { it.id })
        // Picking the unset value widens the list back.
        assertEquals(60, dinner.with(Facet.MEAL, "any").keep(all).size)
        assertEquals(five.cleared(Facet.SPICE), five.with(Facet.SPICE, "any"))
    }

    @Test fun theScriptAndTryNarrowTheListToTwoToFive() {
        for (s in Finder.SCRIPT + Finder.EXAMPLES) {
            // The filters each sentence is expected to set are the sieve's gold, and the Mac host gave exactly those.
            assertEquals(s.id, Fixtures.sieve.getValue(s.id)["text"], s.text)
            assertEquals(s.id, Fixtures.gold(s.id), s.expected)
            assertEquals(s.id, Fixtures.macAnswers(s.id), s.expected)
            assertEquals(s.id, true, Fixtures.mac.getValue(s.id)["all_correct"])
            val kept = s.expected.keep(Fixtures.recipes)
            println("${s.id} \"${s.text}\" -> ${kept.size} of 60: ${kept.joinToString { it.name }}")
            assertTrue("${s.id} keeps ${kept.size}", kept.size in 2..5)
        }
        // The recording's two sentences: all five filters, then three.
        assertEquals(listOf(5, 3), Finder.SCRIPT.map { it.expected.setCount })
    }

    @Test fun theCardLineSaysWhyARecipeIsThere() {
        val curry = Fixtures.recipes.single { it.name == "Red Lentil Curry" }
        val five = Filters.of(meal = "dinner", diet = "vegan", time = "under_60", exclude = "nuts", spice = "medium")
        assertEquals("dinner · vegan · 40 min · medium · no nuts", RecipeLine.text(curry, five))
        // Every part colored by the filter it satisfies.
        assertEquals(listOf(Facet.MEAL, Facet.DIET, Facet.TIME, Facet.SPICE, Facet.WITHOUT), RecipeLine.parts(curry, five).map { it.facet })
        // Unset filters: the plain line, and what the recipe contains when it contains any of the three.
        assertEquals("dinner · vegan · 40 min · medium", RecipeLine.text(curry, Filters()))
        assertEquals(listOf(null, null, null, null), RecipeLine.parts(curry, Filters()).map { it.facet })
        val cookies = Fixtures.recipes.single { it.name == "Almond Butter Cookies" }
        assertEquals("dessert · vegan · 25 min · mild · with nuts, gluten", RecipeLine.text(cookies, Filters()))
        val dessert = Filters.of(meal = "dessert", diet = "vegan", exclude = "nuts")
        val sorbet = Fixtures.recipes.single { it.name == "Mango Sorbet" }
        assertEquals("dessert · vegan · 15 min · mild · no nuts", RecipeLine.text(sorbet, dessert))
        assertEquals(listOf(Facet.MEAL, Facet.DIET, null, null, Facet.WITHOUT), RecipeLine.parts(sorbet, dessert).map { it.facet })
    }
}
