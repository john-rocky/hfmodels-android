package io.github.johnrocky.hfmodels.samples.loops

/**
 * A question the app ships. [gold] is the known final answer of a GSM8K question (the line under each answer marks it);
 * null for the everyday questions, which have no single right text.
 */
data class Example(val id: String, val title: String, val text: String, val gold: String? = null, val source: String)

object Examples {
    /**
     * Appended to each GSM8K question: the instruction the model card's GSM8K numbers were measured with (thinking off,
     * greedy), so the app's answers can be read against them.
     */
    const val GSM8K_INSTRUCTION = "\n\nSolve this step by step. After your reasoning, write the final answer on its own line in the exact form:\n#### <number>"

    private fun gsm8k(index: Int, title: String, question: String, gold: String) =
        Example("gsm8k-$index", title, question + GSM8K_INSTRUCTION, gold, "GSM8K test question $index (MIT)")

    /**
     * Four everyday questions, then four GSM8K test questions on which the two files disagreed in the model card's run
     * (Mac GPU, thinking off): on 69, 54 and 98 only two loops gave the right number, on 88 only one loop did.
     */
    val ALL: List<Example> = listOf(
        Example("dates", "Dates in a sentence", "Extract the dates from: The meeting moved from May 3 to May 17.", source = "the model card's usage example"),
        Example("recipe", "Scale a recipe", "A recipe for 4 people needs 300 g of flour. How much flour do I need for 6 people?", source = "everyday"),
        Example("arrive", "Arrival time", "I leave home at 8:40 and the trip takes 35 minutes. What time do I arrive?", source = "everyday"),
        Example("reply", "Short reply", "Write a two-sentence reply to a friend who invited me to dinner on Friday: I can come, and I ask what I should bring.", source = "everyday"),
        gsm8k(69, "Allowance (GSM8K)", "Bailey starts with a certain amount of money. Then she receives a weekly allowance of \$5 for 8 weeks. At the end of the 8 weeks, if she has a total of \$100, how much money did Bailey start with?", "60"),
        gsm8k(54, "Kittens (GSM8K)", "The Doubtfire sisters are driving home with 7 kittens adopted from the local animal shelter when their mother calls to inform them that their two house cats have just had kittens. She says that Patchy, the first cat, has had thrice the number of adopted kittens, while Trixie, the other cat, has had 12. How many kittens does the Doubtfire family now have?", "40"),
        gsm8k(98, "Traffic jam (GSM8K)", "Cars have lined up on the motorway. Some of the cars drive through in the first 15 minutes of the traffic jam, then 20 more cars drive through in the remaining 15 minutes of the jam. 5 cars from the line take an exit so they don't have to drive through the traffic jam. If there were originally 30 cars on the motorway, how many cars drove through the traffic jam in the first 15 minutes?", "5"),
        gsm8k(88, "Records (GSM8K)", "Marilyn's first record sold 10 times as many copies as Harald's. If they sold 88,000 copies combined, how many copies did Harald sell?", "8000"),
    )

    fun byId(id: String): Example? = ALL.firstOrNull { it.id == id }
}
