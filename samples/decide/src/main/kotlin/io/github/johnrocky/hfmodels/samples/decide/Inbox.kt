package io.github.johnrocky.hfmodels.samples.decide

import android.content.ContentResolver
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.TypedDecisions
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the inbox screen sorts and how, kept apart from the screen so the device check runs the same code: the
 * texts (the phone's unread SMS, lines pasted from the clipboard, or the labelled panel), one decide() per text
 * with [InboxPanel.QUESTIONS], and the numbers the screen shows. The model gets the text alone; the sender is
 * only shown.
 */
object Inbox {
    /** One text: an id (the SMS row's, or the line's index), the sender as shown, the text the model reads, the panel's label. */
    class Text(val id: Long, val sender: String, val text: String, val label: String? = null)

    /** One answer: the option picked, its index in [InboxPanel.OPTIONS], every option's probability, the SDK's milliseconds for the call. */
    class Sorted(val text: Text, val choice: String, val bin: Int, val probabilities: Map<String, Double>, val ms: Double)

    /** What an import gave: texts to sort, or a sentence for the screen that says why there are none. */
    sealed class Import {
        class Texts(val texts: List<Text>) : Import()
        class Empty(val message: String) : Import()
    }

    /** The sender every pasted text is shown with. */
    const val PASTED = "pasted"
    const val EMPTY_PASTE = "The clipboard has no text. Copy your texts, one per line, then tap Paste."
    const val NO_UNREAD = "No unread texts in your inbox. Paste texts instead, one per line."

    /** The clipboard's text as texts to sort: one per line, trimmed, blank lines dropped. */
    fun paste(clip: CharSequence?): Import {
        val lines = clip?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (lines.isEmpty()) return Import.Empty(EMPTY_PASTE)
        return Import.Texts(lines.mapIndexed { i, s -> Text(i.toLong(), PASTED, s) })
    }

    /**
     * The unread texts of the SMS inbox (`content://sms/inbox` where `read = 0`), newest first, at most [limit]:
     * the row id, the sender as a phone shows it, the text. Nothing else is read. Needs READ_SMS.
     */
    fun unread(resolver: ContentResolver, limit: Int = Int.MAX_VALUE): Import {
        val out = ArrayList<Text>()
        resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            "${Telephony.Sms.READ} = 0", null, "${Telephony.Sms.DATE} DESC",
        )?.use { c -> while (c.moveToNext() && out.size < limit) out += Text(c.getLong(0), sender(c.getString(1) ?: ""), c.getString(2) ?: "") }
        return if (out.isEmpty()) Import.Empty(NO_UNREAD) else Import.Texts(out)
    }

    private fun sender(address: String): String =
        if (Regex("""\+?\d{7,15}""").matches(address)) PhoneNumberUtils.formatNumber(address, "US") ?: address else address

    /** The 24 hand-labelled texts of [InboxPanel], each shown with its number and label. */
    fun panel(): List<Text> = InboxPanel.ROWS.mapIndexed { i, (label, text) -> Text(i.toLong(), "#%02d · label %s".format(Locale.US, i + 1, label), text, label) }

    /** One text, one decide(). Blocks the calling thread for the forward: call it off the main thread. */
    suspend fun sortOne(model: TypedDecisions, t: Text): Sorted {
        val d = model.decide(t.text, InboxPanel.QUESTIONS)
        val a = d.answers.getValue("need") as Answer.Choice
        return Sorted(t, a.choice, InboxPanel.OPTIONS.indexOf(a.choice), a.probabilities, d.timing.totalMs)
    }

    /**
     * Every text in order, each decide() on [context]; [onEach] gets the index and the answer on the caller's
     * dispatcher after every text. Cancelling the caller stops the sort after the text in progress (a forward is
     * not cut half-way); the model stays usable and the next sort starts from the first text again.
     */
    suspend fun sort(model: TypedDecisions, texts: List<Text>, context: CoroutineContext = Dispatchers.Default, onEach: (Int, Sorted) -> Unit = { _, _ -> }): List<Sorted> {
        val out = ArrayList<Sorted>(texts.size)
        for ((i, t) in texts.withIndex()) {
            val s = withContext(context) { sortOne(model, t) }
            out += s
            onEach(i, s)
        }
        return out
    }

    /** The answers against the panel's labels ([InboxPanel.LABEL_OPTION]), the scam rows left out: (agree, of). */
    fun agreement(sorted: List<Sorted>): Pair<Int, Int> {
        val labelled = sorted.filter { it.text.label != null && it.text.label != "scam" }
        return labelled.count { s -> s.text.label?.let { InboxPanel.LABEL_OPTION[it] } == s.choice } to labelled.size
    }

    /** What the screen shows for a failed load or sort: the SDK's code first, then its reason. */
    fun failure(e: ModelException): String = "${e.code}: ${e.reason}"

    fun median(xs: List<Double>): Double = xs.sorted().let { s -> if (s.isEmpty()) Double.NaN else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    fun p90(xs: List<Double>): Double = xs.sorted().let { s -> if (s.isEmpty()) Double.NaN else s[(ceil(0.9 * s.size).toInt() - 1).coerceIn(0, s.size - 1)] }
}
