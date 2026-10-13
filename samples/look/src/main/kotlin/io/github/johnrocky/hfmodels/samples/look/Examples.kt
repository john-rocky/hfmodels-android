package io.github.johnrocky.hfmodels.samples.look

/** A bundled example: a photo in assets and the question asked about it when nothing is typed. */
class Example(val id: String, val asset: String, val question: String)

/** The example questions (the chips under the question box) and the example photos (the Example button). */
object Examples {
    /** As the screen shows them and as the model gets them. */
    val QUESTIONS = listOf(
        "What does this sign say?",
        "What is this and how do I use it?",
        "Describe this scene in one sentence",
    )

    /**
     * The photos the Example button cycles through, in assets/examples/. a01_red_bicycle.jpg is CC0 1.0
     * (Openverse a9e0bff3-3272-4c3a-bbd4-92e542e1f41a, a Flickr photo; 1023 x 728, sha256 4147220d…), the
     * photo of probes/eg2demo/fixtures/album.json a01 and of the ask sample's device check.
     */
    val PHOTOS = listOf(
        Example("bicycle", "examples/a01_red_bicycle.jpg", "What is this a photo of?"),
    )

    fun photo(id: String?): Example? = PHOTOS.firstOrNull { it.id == id }

    /** The example after [current] (the first one when none is shown). */
    fun next(current: Example?): Example = PHOTOS[(PHOTOS.indexOf(current) + 1) % PHOTOS.size]
}
