package io.github.johnrocky.hfmodels.samples.decide

import io.github.johnrocky.hfmodels.decide.Question

/**
 * The question the inbox screen asks of every text, and the 24 hand-labelled texts that measure it
 * before anything is recorded (`InboxActivity --ez panel true`). The model gets the text alone; the
 * sender is only shown.
 */
object InboxPanel {
    /** The options, "nothing" first; the key is the option text the model reads. */
    val OPTIONS = listOf(
        "nothing you need to do",
        "a code to type in",
        "a package or a delivery",
        "money you have to pay",
        "an appointment or a booking",
    )

    /** The short names on the chips and the bars, in OPTIONS order. */
    val SHORT = listOf("nothing to do", "code", "delivery", "payment", "appointment")

    val QUESTIONS: Map<String, Question> = linkedMapOf(
        "need" to Question.Choice("What does this text message need from you?", LinkedHashMap(OPTIONS.associateWith { null })),
    )

    /** A panel label -> the option that agrees with it (a text waiting for your reply asks nothing else of you); `scam` rows are left out. */
    val LABEL_OPTION = mapOf(
        "nothing" to OPTIONS[0], "reply" to OPTIONS[0], "code" to OPTIONS[1], "delivery" to OPTIONS[2],
        "bill" to OPTIONS[3], "appointment" to OPTIONS[4],
    )

    /**
     * The panel (label, text): a copy of the 24 texts the questions were measured on, with the names of
     * real businesses replaced by invented ones (NAMES.txt).
     */
    val ROWS: List<Pair<String, String>> = listOf(
        "nothing" to "Thanks for shopping at Marlowe Grocers. Your receipt total was \$42.18. See you again!",
        "nothing" to "FLASH SALE this weekend only: 30% off all shoes at Heelgrove Outlet. Show this text in store.",
        "nothing" to "Hey it's Priya, just landed safely. Talk tomorrow!",
        "nothing" to "Your table for 2 at Casa Verdino is confirmed for tonight 7:30 pm. Reply STOP to opt out of texts.",
        "code" to "Your Kelderbank verification code is 483920. It expires in 10 minutes. Do not share it.",
        "code" to "G-771204 is your sign-in code for Mailbrook.",
        "code" to "Use 30591 to confirm your phone number on Mailbrook. The code is valid for 5 minutes.",
        "delivery" to "Parcelwick: your package will arrive today between 2 pm and 6 pm. Someone must be home to sign.",
        "delivery" to "Your order #88231 has been delivered to the front porch.",
        "delivery" to "We missed you! Your parcel is waiting at Elm Street Post Office. Bring ID to collect it within 7 days.",
        "delivery" to "Courier update: driver is 3 stops away. Package requires a signature.",
        "bill" to "Reminder: your electricity bill of \$128.40 is due on Friday 3 Oct. Pay online to avoid a late fee.",
        "bill" to "Your car insurance payment of \$96 failed. Update your card by Sept 30 to keep cover.",
        "bill" to "Invoice INV-2291 from Brookpell Plumbing for \$340 is now 5 days overdue.",
        "appointment" to "Dr. Okafor's office: your appointment is Tue 30 Sept at 9:15 am. Reply C to confirm or R to reschedule.",
        "appointment" to "Molarbrook Dental: we have an opening tomorrow at 11 am. Text YES to take it.",
        "appointment" to "Your haircut with Lena is booked for Sat 4 Oct at 3 pm at Snip & Sable.",
        "appointment" to "Vehicle inspection reminder: your slot is Mon 6 Oct 8:00 am at Brackwheel Motors. Bring the registration.",
        "reply" to "Mom: are you coming for dinner on Sunday? Dad wants to know how many to cook for",
        "reply" to "Hi, this is Daniel from the estate agency. Would 5 pm on Thursday work for the viewing?",
        "reply" to "Can you send me the photos from Saturday when you get a chance?",
        "scam" to "Your account has been locked. Verify your identity now at http://kelderbank-secure-login.xyz or lose access.",
        "scam" to "Hi Mum this is my new number, my phone broke. Can you transfer 300 for the repair today? I'll pay you back.",
        "scam" to "You have won a \$1000 gift card! Send the 6-digit code we just texted you to claim it.",
    )
}
