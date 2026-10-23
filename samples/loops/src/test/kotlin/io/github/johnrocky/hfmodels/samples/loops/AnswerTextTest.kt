package io.github.johnrocky.hfmodels.samples.loops

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The answer as a column shows it: Markdown's marks dropped, bold kept as bold, LaTeX written in plain symbols, while it
 * streams and at the end. The answers are the model's, byte for byte: the Galaxy S26 runs of 2026-10-23
 * (`results/2026-10-23-s26/`) and lines of the model card's GSM8K run on a Mac GPU.
 */
class AnswerTextTest {
    private fun bold(s: AnswerText.Shown) = s.bold.map { s.text.substring(it.first, it.last + 1) }

    @Test
    fun theBookAnswer() {
        // 1 loop, your own question in a.json.
        val raw = "You will need **15.2 days** to finish the book.\n\nHere is the breakdown:\n*   **Total pages:** 380\n*   **Pages per day:** 25\n*   **Calculation:** 380 ÷ 25 = 15.2\n\nSince you cannot read a fraction of a day in this context, you would need **16 full days** if you want to finish the book by the end of the day"
        val s = AnswerText.render(raw)
        assertEquals("You will need 15.2 days to finish the book.\n\nHere is the breakdown:\n• Total pages: 380\n• Pages per day: 25\n• Calculation: 380 ÷ 25 = 15.2\n\nSince you cannot read a fraction of a day in this context, you would need 16 full days if you want to finish the book by the end of the day", s.text)
        assertEquals(listOf("15.2 days", "Total pages:", "Pages per day:", "Calculation:", "16 full days"), bold(s))
    }

    @Test
    fun displayAndInlineMath() {
        // 1 loop, gsm8k-88 in b1.json.
        val one = "Let \\( H \\) be the number of copies Harald sold. Then Marilyn's record sold \\( 10H \\) copies.\n\nThe combined total is:\n\\[\nH + 10H = 11H = 88{,}000\n\\]\n\nSolve for \\( H \\):\n\\[\nH = \\frac{88{,}000}{11}\n\\]\n\nDivide:\n\\[\n88{,}000 \\div 11 = 8{,}000\n\\]\n\nSo Harald sold 8,000 copies.\n\n#### 8000"
        assertEquals("Let H be the number of copies Harald sold. Then Marilyn's record sold 10H copies.\n\nThe combined total is:\nH + 10H = 11H = 88,000\n\nSolve for H:\nH = 88,000/11\n\nDivide:\n88,000 ÷ 11 = 8,000\n\nSo Harald sold 8,000 copies.\n\n#### 8000", AnswerText.render(one).text)
        // 2 loops, the same question: indented formulas under numbered steps.
        val two = "Let \\( H \\) represent the number of copies Harald sold.\n\n1.  **Set up the relationship:**\n    Marilyn's sales are 10 times Harald's sales.\n    \\[\n    \\text{Marilyn's sales} = 10H\n    \\]\n\n2.  **Set up the combined total:**\n    The sum of Marilyn's and Harald's sales is 88,000 copies.\n    \\[\n    H + 10H = 88,000\n    \\]\n\n3.  **Solve for \\( H \\):**\n    Combine the terms on the left side:\n    \\[\n    11H = 88,000\n    \\]\n    Divide both sides by 11:\n    \\[\n    H = \\frac{88,000}{11}\n    \\]\n    \\[\n    H = 8,000\n    \\]\n\nHarald sold 8,000 copies.\n\n#### 8000"
        val s = AnswerText.render(two)
        assertEquals("Let H represent the number of copies Harald sold.\n\n1. Set up the relationship:\n    Marilyn's sales are 10 times Harald's sales.\n    Marilyn's sales = 10H\n\n2. Set up the combined total:\n    The sum of Marilyn's and Harald's sales is 88,000 copies.\n    H + 10H = 88,000\n\n3. Solve for H:\n    Combine the terms on the left side:\n    11H = 88,000\n    Divide both sides by 11:\n    H = 88,000/11\n    H = 8,000\n\nHarald sold 8,000 copies.\n\n#### 8000", s.text)
        assertEquals(listOf("Set up the relationship:", "Set up the combined total:", "Solve for H:"), bold(s))
    }

    @Test
    fun dollarMathAndDollarSigns() {
        // 2 loops, recipe in b1.json: the formulas are in $ pairs.
        val recipe = "To find the amount of flour needed for 6 people, you can use a simple proportion:\n\n1.  **Find the amount of flour per person:**\n    \$300\\text{ g} \\div 4\\text{ people} = 75\\text{ g per person}\$\n\n2.  **Multiply by the number of people:**\n    \$75\\text{ g} \\times 6\\text{ people} = 450\\text{ g}\$\n\nYou will need **450 g** of flour"
        assertEquals("To find the amount of flour needed for 6 people, you can use a simple proportion:\n\n1. Find the amount of flour per person:\n    300 g ÷ 4 people = 75 g per person\n\n2. Multiply by the number of people:\n    75 g × 6 people = 450 g\n\nYou will need 450 g of flour", AnswerText.render(recipe).text)
        // 2 loops, gsm8k-69 in b1.json: every $ is a price, and the text stays as the model wrote it.
        val prices = "Bailey receives a weekly allowance of \$5 for 8 weeks. First, we calculate the total amount she receives from her allowance:\n\$5 per week × 8 weeks = \$40.\n\nAt the end of the 8 weeks, her total amount is \$100. This total consists of her starting amount plus the \$40 she earned. To find her starting amount, we subtract the allowance from the total:\n\$100 (total) - \$40 (allowance) = \$60.\n\nTherefore, Bailey started with \$60.\n\n#### 60"
        assertEquals(prices, AnswerText.render(prices).text)
        // Mac GPU, GSM8K 5, 15 and 2: math pairs between prices, prices in a comparison, escaped dollars inside math.
        assertEquals("The price of each glass is multiplied by 0.6 to get the price of the next glass. This is a geometric sequence where the first term (a₁) is \$5 and the common ratio (r) is 0.6.",
            AnswerText.render("The price of each glass is multiplied by 0.6 to get the price of the next glass. This is a geometric sequence where the first term (\$a_1\$) is \$5 and the common ratio (\$r\$) is \$0.6\$.").text)
        assertEquals("• The merchant should choose the jewelry plan because it results in a higher profit (\$125 > \$96).",
            AnswerText.render("*   The merchant should choose the jewelry plan because it results in a higher profit (\$125 > \$96).").text)
        assertEquals("150% of \$80,000 = 1.5 × \$80,000 = \$120,000", AnswerText.render("\$150\\% \\text{ of } \\\$80,000 = 1.5 \\times \\\$80,000 = \\\$120,000\$").text)
    }

    @Test
    fun fractionsScriptsAndDelimiters() {
        // Mac GPU, GSM8K 5, 13, 38, 57 (1 loop) and 88 (2 loops), one line each.
        assertEquals("aₙ = a₁ · rⁿ⁻¹", AnswerText.render("\$a_n = a_1 \\cdot r^{n-1}\$").text)
        assertEquals("Sₙ = a₁ (1 - rⁿ)/(1 - r)", AnswerText.render("\$S_n = a_1 \\frac{1 - r^n}{1 - r}\$").text)
        assertEquals("First, calculate 0.6¹⁵:", AnswerText.render("First, calculate \$0.6^{15}\$:").text)
        // 2 loops, GSM8K 93: a subscript with no subscript letters is written out.
        assertEquals("v_new = 1.10 × v_original", AnswerText.render("\\( v_{\\text{new}} = 1.10 \\times v_{\\text{original}} \\)").text)
        assertEquals("    1/2 (2/3x - 2) = 5", AnswerText.render("    \$\\frac{1}{2} \\left( \\frac{2}{3}x - 2 \\right) = 5\$").text)
        assertEquals("Speed = (Total Distance)/(Total Time)", AnswerText.render("\$\$\\text{Speed} = \\frac{\\text{Total Distance}}{\\text{Total Time}}\$\$").text)
        assertEquals("Speed = (60 miles)/(6 hours) = 10 miles per hour", AnswerText.render("\$\$\\text{Speed} = \\frac{60 \\text{ miles}}{6 \\text{ hours}} = 10 \\text{ miles per hour}\$\$").text)
        assertEquals("3755 + (15 × n) ≤ 5000", AnswerText.render("\$\$3755 + (15 \\times n) \\le 5000\$\$").text)
        assertEquals("If we use the exact fraction 88,000/15, the exact value is 5,866.6\u0305. However, in the context of counting physical copies, the answer is typically rounded to the nearest whole number.",
            AnswerText.render("If we use the exact fraction \\( \\frac{88,000}{15} \\), the exact value is \\( 5,866.\\overline{6} \\). However, in the context of counting physical copies, the answer is typically rounded to the nearest whole number.").text)
    }

    @Test
    fun theReadmeExamples() {
        assertEquals("H = 88,000/11", AnswerText.render("\\[ H = \\frac{88{,}000}{11} \\]").text)
        assertEquals("75 g × 6", AnswerText.render("\$75\\text{ g} \\times 6\$").text)
        assertEquals("\$5 per week × 8 weeks = \$40", AnswerText.render("\$5 per week × 8 weeks = \$40").text)
    }

    @Test
    fun whileItStreams() {
        // Half of a command, an argument still open, a lone * and a $ are held back, never shown and then taken away.
        assertEquals("Then ", AnswerText.render("Then \\fr", partial = true).text)
        assertEquals("H = ", AnswerText.render("H = \\frac{88{,}000}{1", partial = true).text)
        assertEquals("H =", AnswerText.render("\\[\nH = \\frac{88{,}0", partial = true).text)
        assertEquals("\$a", AnswerText.render("\$a_", partial = true).text)
        assertEquals("costs ", AnswerText.render("costs \$", partial = true).text)
        val half = AnswerText.render("You will need **450 g*", partial = true)
        assertEquals("You will need 450 g", half.text)
        assertEquals(listOf("450 g"), bold(half))
        // A formula's own line is dropped as soon as it is there.
        assertEquals("multiply:", AnswerText.render("multiply:\n\\[", partial = true).text)
        // The end of the answer is shown whole: nothing more is coming.
        assertEquals("Then \\fr", AnswerText.render("Then \\fr").text)
    }

    @Test
    fun fencesHeadingsAndTheGsm8kLine() {
        val s = AnswerText.render("### Plan\n```kotlin\nval half = a_b * \\frac{1}{2}\n```\n#### 60")
        assertEquals("Plan\n```kotlin\nval half = a_b * \\frac{1}{2}\n```\n#### 60", s.text)
        assertEquals(listOf("Plan"), bold(s))
    }

    @Test
    fun textWithoutMarksIsUnchanged() {
        for (plain in listOf("May 3, May 17", "May 3\nMay 17", "I can't wait to see you! What should I bring", "2 * 3 = 6 🌿", "C:\\Users\\me\\big\\.git", "")) {
            assertEquals(AnswerText.Shown(plain, emptyList()), AnswerText.render(plain))
        }
    }
}
