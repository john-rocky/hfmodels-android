package io.github.johnrocky.hfmodels.samples.decide.sms

import java.util.Locale
import kotlin.random.Random

/** One synthetic text: the sender as the phone stores it, the body, what it was written as, and how old it is. */
data class SyntheticSms(val from: String, val text: String, val kind: String, val minutesAgo: Int)

/** A business sender: the id the phone shows (at most 11 characters, as an alphanumeric sender id) and the name inside the text. */
data class Brand(val sender: String, val name: String)

/** A text pattern: what it was written as, who sends it (a brand role, "person" or "unknown"), the text with `{slot}`s. */
data class SmsTemplate(val kind: String, val from: String, val text: String)

/**
 * Everything the generator draws from. All names are invented; `samples/decide/NAMES.txt` lists every one
 * of them so they can be searched before a recording. Numbers are in the range kept for fiction
 * (+1 NXX 555-0100 to 555-0199); links end in `.example`, a domain that never resolves.
 */
data class SmsVocabulary(
    val brands: Map<String, List<Brand>>,
    val people: List<String>,
    val surnames: List<String>,
    val pets: List<String>,
    val templates: List<SmsTemplate>,
    /** Percent of each kind in an inbox; the kinds are the template kinds. */
    val mix: Map<String, Int>,
) {
    companion object {
        val DEFAULT = SmsVocabulary(
            brands = mapOf(
                "bank" to listOf(Brand("Kelderbank", "Kelderbank"), Brand("LorrowCU", "Lorrow Credit Union")),
                "app" to listOf(Brand("Mailbrook", "Mailbrook")),
                "courier" to listOf(Brand("Parcelwick", "Parcelwick"), Brand("Crateford", "Crateford Couriers")),
                "store" to listOf(Brand("Heelgrove", "Heelgrove")),
                "grocer" to listOf(Brand("HobnailMkt", "Hobnail Market")),
                "energy" to listOf(Brand("Brindlewatt", "Brindlewatt Energy")),
                "phone" to listOf(Brand("Chattermoss", "Chattermoss Mobile")),
                "water" to listOf(Brand("Cobblemere", "Cobblemere Water")),
                "insurer" to listOf(Brand("Shieldwren", "Shieldwren Insurance")),
                "dentist" to listOf(Brand("Molarbrook", "Molarbrook Dental")),
                "clinic" to listOf(Brand("Lindenquay", "Lindenquay Clinic")),
                "salon" to listOf(Brand("SnipSable", "Snip & Sable")),
                "garage" to listOf(Brand("Brackwheel", "Brackwheel Motors")),
                "restaurant" to listOf(Brand("CasaUmbrino", "Casa Umbrino")),
                "gym" to listOf(Brand("Lungefold", "Lungefold Fitness")),
                "cinema" to listOf(Brand("Lanternby", "Lanternby Cinemas")),
                "vet" to listOf(Brand("Tallowby", "Tallowby Vets")),
                "plumber" to listOf(Brand("Brookpell", "Brookpell Plumbing")),
                "agency" to listOf(Brand("Keyfallow", "Keyfallow Homes")),
            ),
            people = listOf("Maya", "Tom", "Aisha", "Ben", "Chloe", "Sam", "Nina", "Leo", "Grace", "Omar", "Ruby", "Jake", "Hannah", "Luis"),
            surnames = listOf("Lindqvist", "Okoro", "Brennan", "Castell", "Hartley", "Novak", "Ferreira", "Whitlow"),
            pets = listOf("Biscuit", "Milo", "Pepper"),
            templates = TEMPLATES,
            mix = mapOf("nothing" to 34, "code" to 14, "delivery" to 15, "bill" to 11, "appointment" to 11, "reply" to 10, "scam" to 5),
        )
    }
}

/**
 * Synthetic English texts for the inbox screen and its seeding helper (`probes/smsseed`): the same `count`
 * and `seed` always give the same list. The first text is the newest; the texts are spread over the last
 * 14 days. No text repeats.
 */
object SmsGenerator {
    const val DEFAULT_SEED = 7L
    private const val SPAN_MINUTES = 14 * 24 * 60

    fun generate(count: Int, seed: Long = DEFAULT_SEED, vocabulary: SmsVocabulary = SmsVocabulary.DEFAULT): List<SyntheticSms> {
        require(count >= 0) { "count must be >= 0, got $count" }
        val rnd = Random(seed)
        val byKind = vocabulary.templates.groupBy { it.kind }
        val kinds = vocabulary.mix.entries.filter { it.value > 0 }
        require(kinds.all { byKind.containsKey(it.key) }) { "every kind in the mix needs a template" }
        val weight = kinds.sumOf { it.value }
        val numbers = personNumbers(vocabulary.people)
        // Gaps between texts, scaled so the oldest is just inside 14 days.
        val gaps = DoubleArray(count) { 0.3 + rnd.nextDouble() }
        val total = gaps.sum()
        val out = ArrayList<SyntheticSms>(count)
        val seen = HashSet<String>()
        val recent = ArrayDeque<SmsTemplate>()
        var acc = 0.0
        var attempts = 0
        while (out.size < count) {
            check(++attempts <= count * 50 + 100) { "the vocabulary cannot give $count different texts" }
            var r = rnd.nextInt(weight)
            val kind = kinds.first { r -= it.value; r < 0 }.key
            val template = byKind.getValue(kind).let { it[rnd.nextInt(it.size)] }
            if (template in recent) continue
            val (from, text) = Filler(rnd, vocabulary, numbers).fill(template)
            if (from == out.lastOrNull()?.from || !seen.add(text)) continue
            recent.addLast(template)
            if (recent.size > 2) recent.removeFirst()
            acc += gaps[out.size]
            out += SyntheticSms(from, text, kind, 3 + (acc / total * (SPAN_MINUTES - 10)).toInt())
        }
        return out
    }

    /** Each person texts from one number: +1, an area code, 555-01xx (the range kept for fiction). */
    fun personNumbers(people: List<String>): Map<String, String> =
        people.withIndex().associate { (i, name) -> name to "+1${AREAS[i % AREAS.size]}55501${"%02d".format(Locale.ROOT, 10 + i * 3)}" }

    /** True for a number the generator makes: +1, an area code, 555-0100 to 555-0199. */
    fun isFictionalNumber(s: String) = Regex("""\+1\d{3}55501\d{2}""").matches(s)

    private val AREAS = listOf("201", "312", "415", "503", "617", "720", "808")
}

private class Filler(val rnd: Random, val v: SmsVocabulary, val numbers: Map<String, String>) {
    private val chosen = HashMap<String, String>()

    fun fill(t: SmsTemplate): Pair<String, String> {
        val from = when (t.from) {
            "person" -> numbers.getValue(person())
            // A scam comes from a number nobody has saved: the upper half of the fictional range.
            "unknown" -> "+1${pick(listOf("213", "305", "469", "646", "702"))}55501${50 + rnd.nextInt(50)}"
            else -> brand(t.from).sender
        }
        val text = Regex("""\{(\w+)\}""").replace(t.text) { slot(it.groupValues[1]) }
        return from to text
    }

    private fun person(): String = chosen.getOrPut("person") { pick(v.people) }
    private fun brand(role: String): Brand {
        val name = chosen.getOrPut("brand:$role") { pick(v.brands.getValue(role)).name }
        return v.brands.getValue(role).first { it.name == name }
    }

    private fun <T> pick(xs: List<T>): T = xs[rnd.nextInt(xs.size)]
    private fun digits(n: Int) = (1..n).joinToString("") { (if (it == 1) 1 + rnd.nextInt(9) else rnd.nextInt(10)).toString() }
    private fun money(lo: Int, hi: Int) = "%.2f".format(Locale.ROOT, lo + rnd.nextInt((hi - lo) * 100) / 100.0)

    private fun slot(name: String): String = chosen.getOrPut(name) {
        when (name) {
            "name" -> person()
            "other" -> pick(v.people.filter { it != chosen["person"] })
            "surname" -> pick(v.surnames)
            "pet" -> pick(v.pets)
            "amount" -> money(12, 480)
            "big" -> (200 + rnd.nextInt(19) * 50).toString()
            "small" -> money(1, 5)
            "prize" -> pick(listOf("500", "1000", "2500"))
            "code6" -> digits(6)
            "code5" -> digits(5)
            "code4" -> digits(4)
            "order" -> digits(5)
            "inv" -> "INV-" + digits(4)
            "last4" -> digits(4)
            "tracking" -> brand("courier").sender.take(2).uppercase(Locale.ROOT) + digits(9)
            "pct" -> pick(listOf("15", "20", "25", "30", "40", "50"))
            "pts" -> (20 + rnd.nextInt(380)).toString()
            "n" -> (2 + rnd.nextInt(5)).toString()
            "visits" -> (8 + rnd.nextInt(13)).toString()
            "gb" -> (3 + rnd.nextInt(12)).toString()
            "cap" -> pick(listOf("20", "30", "50"))
            "weekday" -> pick(WEEKDAYS)
            "day2" -> pick(WEEKDAYS.filter { it != slot("weekday") })
            "time" -> pick(TIMES)
            "window" -> pick(WINDOWS)
            "date" -> "${1 + rnd.nextInt(28)} ${pick(MONTHS_SHORT)}"
            "month" -> pick(MONTHS)
            "thing" -> pick(THINGS)
            "banklink" -> "http://" + brand("bank").sender.lowercase(Locale.ROOT) + "-" + pick(LINK_WORDS) + ".example"
            "courierlink" -> "http://" + brand("courier").sender.lowercase(Locale.ROOT) + "-" + pick(LINK_WORDS) + ".example"
            "phonelink" -> "http://" + brand("phone").sender.lowercase(Locale.ROOT) + "-" + pick(LINK_WORDS) + ".example"
            else -> {
                // {bank}, {courier}, ...: the brand's name in the text, the same brand as the sender.
                require(v.brands.containsKey(name)) { "unknown slot {$name}" }
                brand(name).name
            }
        }
    }

    companion object {
        val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val TIMES = listOf("8:30 am", "9:15 am", "10 am", "11:45 am", "1:30 pm", "2 pm", "3:15 pm", "4:40 pm", "6 pm", "7:30 pm")
        val WINDOWS = listOf("8 am and 12 pm", "10 am and 2 pm", "12 pm and 4 pm", "2 pm and 6 pm", "4 pm and 8 pm")
        val MONTHS = listOf("August", "September", "October")
        val MONTHS_SHORT = listOf("Oct", "Nov")
        val THINGS = listOf("the trip", "the quote", "the lease", "the party budget", "the car", "the rota")
        val LINK_WORDS = listOf("secure-login", "verify", "account-check", "billing-update")
    }
}

private val TEMPLATES = listOf(
    // nothing you need to do: receipts, promotions, greetings, notices
    SmsTemplate("nothing", "grocer", "Thanks for shopping at {grocer}. Your receipt total was \${amount}. You earned {pts} points today."),
    SmsTemplate("nothing", "grocer", "{grocer}: this week only, 2 for 1 on fresh bread and {pct}% off all fruit. See you in store!"),
    SmsTemplate("nothing", "store", "{store}: thanks for your order #{order}. Your receipt is in your account, no action needed."),
    SmsTemplate("nothing", "store", "{store} SALE: {pct}% off everything until Sunday. Shop online or in store."),
    SmsTemplate("nothing", "store", "{store}: your return has arrived and \${amount} is back on your card."),
    SmsTemplate("nothing", "gym", "{gym}: new spin classes start {weekday} at {time}. Just drop in, no booking needed."),
    SmsTemplate("nothing", "gym", "{gym}: the pool reopens on {weekday} after cleaning. Thanks for your patience."),
    SmsTemplate("nothing", "cinema", "{cinema}: your tickets for {weekday} are confirmed, 2 seats in row F. Show this text at the door."),
    SmsTemplate("nothing", "energy", "{energy}: your {month} statement is ready. Your account is in credit, so there is nothing to pay."),
    SmsTemplate("nothing", "phone", "{phone}: your data resets tomorrow. You used {gb} GB of {cap} GB this month."),
    SmsTemplate("nothing", "bank", "{bank}: a payment of \${amount} to {store} was approved on your card ending {last4}."),
    SmsTemplate("nothing", "water", "{water}: planned work on the mains on {weekday}. Your water supply will not be affected."),
    SmsTemplate("nothing", "restaurant", "{restaurant}: thanks for dining with us on {weekday}. We hope to see you again soon!"),
    SmsTemplate("nothing", "vet", "{vet}: {pet} was a star at the check-up today. All vaccines are up to date."),
    SmsTemplate("nothing", "person", "Hey it's {name}, made it home safe. Thanks for tonight!"),
    SmsTemplate("nothing", "person", "{name} here, just finished the race! Legs are dead but so happy."),
    SmsTemplate("nothing", "person", "Happy birthday!! Hope you have the best day. Love, {name}"),
    SmsTemplate("nothing", "person", "It's {name}, loved the photos from the weekend. Thanks for sending them."),
    SmsTemplate("nothing", "person", "{name} here! We got the keys to the new flat today!!"),
    SmsTemplate("nothing", "person", "Lovely to see you on {weekday}! Let's do it again soon. {name} x"),
    SmsTemplate("nothing", "grocer", "{grocer}: your receipt from {weekday}, \${amount}. Thank you for shopping with us."),
    SmsTemplate("nothing", "gym", "{gym}: {visits} visits this month, keep it up!"),
    SmsTemplate("nothing", "water", "{water}: thanks for your payment of \${amount}. Your account is up to date."),
    SmsTemplate("nothing", "energy", "{energy}: thanks, we received your meter reading for {month}."),
    SmsTemplate("nothing", "bank", "{bank}: your {month} statement is ready to view in the app."),
    SmsTemplate("nothing", "phone", "{phone}: your plan now includes {gb} GB of extra data at no cost."),
    // a code to type in
    SmsTemplate("code", "bank", "{bank}: your verification code is {code6}. It expires in 10 minutes. Never share it."),
    SmsTemplate("code", "bank", "{bank}: use one-time passcode {code6} to approve your new device. It expires in 5 minutes."),
    SmsTemplate("code", "app", "{code6} is your {app} sign-in code."),
    SmsTemplate("code", "app", "Use {code5} to confirm your phone number on {app}. The code is valid for 5 minutes."),
    SmsTemplate("code", "app", "{app}: your login code is {code6}. If you did not ask for it, you can ignore this text."),
    SmsTemplate("code", "app", "Your {app} security code is {code4}. Do not give this code to anyone."),
    SmsTemplate("code", "store", "{store}: {code6} is your code to reset your password."),
    SmsTemplate("code", "phone", "{phone}: enter {code6} to finish setting up your eSIM."),
    // a package or a delivery
    SmsTemplate("delivery", "courier", "{courier}: your parcel {tracking} is out for delivery today between {window}."),
    SmsTemplate("delivery", "courier", "{courier}: we tried to deliver your parcel today but nobody was home. We will try again tomorrow."),
    SmsTemplate("delivery", "courier", "{courier}: your parcel was left at the front door at {time}."),
    SmsTemplate("delivery", "courier", "{courier}: your parcel is waiting at your local pickup point. Collect it within 5 days with photo ID."),
    SmsTemplate("delivery", "courier", "{courier}: your driver is {n} stops away. Your parcel needs a signature."),
    SmsTemplate("delivery", "store", "{store}: your order #{order} has shipped with {courier}. It should arrive on {weekday}."),
    SmsTemplate("delivery", "store", "Good news from {store}: order #{order} arrives tomorrow."),
    SmsTemplate("delivery", "grocer", "{grocer}: your grocery delivery is on its way and will arrive between {window}."),
    // money you have to pay
    SmsTemplate("bill", "energy", "{energy}: your electricity bill of \${amount} is due on {date}. Pay online to avoid a late fee."),
    SmsTemplate("bill", "phone", "{phone}: your bill of \${amount} is due in 3 days. Pay in the app or reply PAY."),
    SmsTemplate("bill", "insurer", "{insurer}: your car insurance payment of \${amount} failed. Update your card by {date} to stay covered."),
    SmsTemplate("bill", "water", "{water}: your water bill of \${amount} is now overdue. Please pay by {date}."),
    SmsTemplate("bill", "plumber", "Invoice {inv} from {plumber} for \${big} is due on {weekday}. Thank you!"),
    SmsTemplate("bill", "vet", "{vet}: the bill for {pet}'s visit, \${amount}, is still open. Please pay online or at your next visit."),
    SmsTemplate("bill", "gym", "{gym}: your membership payment of \${amount} did not go through. Please update your card."),
    SmsTemplate("bill", "bank", "{bank}: your credit card payment of \${amount} is due on {date}."),
    // an appointment or a booking
    SmsTemplate("appointment", "dentist", "{dentist}: reminder of your check-up on {weekday} at {time}. Reply Y to confirm or call us to change it."),
    SmsTemplate("appointment", "dentist", "{dentist}: we have an opening tomorrow at {time}. Text YES to take it."),
    SmsTemplate("appointment", "clinic", "{clinic}: Dr. {surname} can see you on {weekday} at {time}. Reply YES to book this slot."),
    SmsTemplate("appointment", "clinic", "{clinic}: your blood test is on {weekday} at {time}. No food after midnight."),
    SmsTemplate("appointment", "salon", "Your haircut at {salon} is booked for {weekday} at {time}."),
    SmsTemplate("appointment", "garage", "{garage}: your car service is booked for {weekday} at {time}. Please drop off the keys by 8 am."),
    SmsTemplate("appointment", "vet", "{vet}: {pet} is due for a booster. Reply with a day that suits you and we will book it."),
    SmsTemplate("appointment", "restaurant", "{restaurant}: your table for {n} is booked for {weekday} at {time}. Reply C to cancel."),
    // a person waiting for your reply
    SmsTemplate("reply", "person", "Hey, are you free for lunch on {weekday}?"),
    SmsTemplate("reply", "person", "Can you send me the photos from {weekday} when you get a chance?"),
    SmsTemplate("reply", "person", "It's {name}. What time should I pick you up tomorrow?"),
    SmsTemplate("reply", "person", "Hi, this is {name} from {agency}. Would {time} on {weekday} work for the viewing?"),
    SmsTemplate("reply", "person", "Did you see my email about {thing}? Let me know what you think."),
    SmsTemplate("reply", "person", "Are we still on for {weekday}? Let me know by tonight."),
    SmsTemplate("reply", "person", "Which works better for you, {weekday} or {day2}?"),
    SmsTemplate("reply", "person", "{name} here, can you call me back when you get a minute? It's about {thing}."),
    SmsTemplate("reply", "person", "Do you still want the tickets for {weekday}? I need to tell {other} by tonight."),
    SmsTemplate("reply", "person", "Hi, it's {name} from next door. Could you take in a parcel for me on {weekday}?"),
    // asks for money, a code, a password or a login link
    SmsTemplate("scam", "unknown", "{bank}: your account has been locked. Verify your identity now at {banklink} or lose access."),
    SmsTemplate("scam", "unknown", "Hi Mum, I dropped my phone and this is my new number. Can you send \${big} for a new one today? I'll pay you back"),
    SmsTemplate("scam", "unknown", "You have won a \${prize} gift card! Send us the 6-digit code we just texted you to claim it."),
    SmsTemplate("scam", "unknown", "{courier}: your parcel is on hold because of an unpaid fee of \${small}. Pay now at {courierlink}"),
    SmsTemplate("scam", "unknown", "This is the {bank} fraud team. To stop a payment of \${big}, reply with the code we just sent you."),
    SmsTemplate("scam", "unknown", "{phone}: your number will be cut off today. Log in at {phonelink} to keep it."),
    SmsTemplate("scam", "unknown", "Hi Dad, it's me. Lost my wallet, can you send \${amount} to this account so I can get home? Don't call, my battery is dying"),
)
