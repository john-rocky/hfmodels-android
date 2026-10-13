package io.github.johnrocky.hfmodels.samples.look

/** A bundled example: a photo in assets, what it shows (the strip's content description), and its example question. */
class Example(val id: String, val asset: String, val question: String, val shows: String)

/** The example questions (the chips under the question box) and the example photos (the strip under the photo). */
object Examples {
    /** As the screen shows them and as the model gets them. */
    val QUESTIONS = listOf(
        "What does this sign say?",
        "What is this and how do I use it?",
        "Describe this scene in one sentence",
    )

    /**
     * The photos of the strip, in assets/examples/. They are not in the repository: tools/fetch_examples.py downloads
     * them by the URLs, sizes and sha256 of tools/examples.json, which lists the same ids and files (a JVM test keeps
     * the two equal). All three are CC0 1.0; the README says where each one comes from. A tap on one goes through
     * [PhotoImport] like a picked photo; its question is used when recording mode is given none.
     */
    val PHOTOS = listOf(
        Example("sign", "examples/sign.webp", QUESTIONS[0], "a sign in a lawn"),
        Example("object", "examples/object.jpg", QUESTIONS[1], "chess pieces on a board"),
        Example("scene", "examples/scene.jpg", QUESTIONS[2], "a lighthouse by the sea"),
    )

    fun photo(id: String?): Example? = PHOTOS.firstOrNull { it.id == id }
}
