package io.github.johnrocky.hfmodels.samples.promises

/**
 * The built-in conversation behind the Sample button, the recording mode and the device check: 16 sentences
 * of the round-1 sieve's chat fixture (`b_chat.jsonl`: 30 English sentences written for the sieve with their
 * label, promise / request / plan / nothing, and no names in them), word for word, put in the order of a
 * chat between `You` (the phone's owner) and `Them`. 4 promises, 4 requests, 3 plans, 5 nothing.
 *
 * Chosen, not drawn: the promises are given to `You` and the requests to `Them`, so the bundles' titles read
 * right (the model never sees who wrote a sentence), and the three sentences of the 30 that the card's Mac
 * host answered differently from their label (b03, b07, b24) are not here. On all 30 the Mac host matched the
 * label on 27, and the Galaxy S26 gave the Mac host's answer on 30 of 30 (2026-10-03). The conversation opens
 * with a request and ends with a plan: a phone that sorts all 16 within the card's 1.2 s shows those two.
 */
object SampleChat {
    class Line(val id: String, val sender: String, val text: String, val label: String)

    val LINES = listOf(
        Line("b09", "Them", "Can you bring the folding chairs to the party?", "request"),
        Line("b02", "You", "Don't worry, I'll pick up the cake on my way home.", "promise"),
        Line("b18", "You", "So it's dinner at your place on the 12th at 7, right?", "plan"),
        Line("b23", "Them", "That concert was way too loud.", "nothing"),
        Line("b01", "You", "I'll send you the photos tonight.", "promise"),
        Line("b30", "Them", "Ugh, the train is late again.", "nothing"),
        Line("b15", "Them", "Text me when you get home, please.", "request"),
        Line("b06", "You", "I'll bring your charger back next time I see you.", "promise"),
        Line("b26", "Them", "That trip when the car broke down still makes me laugh.", "nothing"),
        Line("b08", "You", "I promise I'll pay you back at the end of the month.", "promise"),
        Line("b11", "Them", "Could you feed the cat tonight?", "request"),
        Line("b14", "Them", "Can you pick up some milk on your way back?", "request"),
        Line("b27", "You", "I'm making coffee right now.", "nothing"),
        Line("b20", "Them", "Great, the park at noon this weekend it is.", "plan"),
        Line("b28", "You", "The new cafe downstairs has really good bread.", "nothing"),
        Line("b17", "You", "Let's meet at the station at 6:30 tonight.", "plan"),
    )

    /** The conversation as a chat app would share it, one `Name: text` line per message. */
    val TEXT: String = LINES.joinToString("\n") { "${it.sender}: ${it.text}" }

    /** The label of a sentence of this conversation, by its text; null for any other sentence. */
    fun labelOf(text: String): String? = LINES.firstOrNull { it.text == text }?.label
}
