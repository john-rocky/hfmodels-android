package io.github.johnrocky.hfmodels.probes.smsseed

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.provider.Telephony
import android.util.Log

// The components the SMS role requires of its holder. The helper holds the role only for the seconds of
// a seed or a clear; these keep the phone's own texts safe in that window and do nothing else.

/** SENDTO sms/smsto/mms/mmsto: the helper does not compose; it closes at once. */
class ComposeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}

/** A real text that arrives while the helper holds the role is stored in the inbox, as the default app would store it. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) return
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, parts[0].displayOriginatingAddress)
            put(Telephony.Sms.BODY, parts.joinToString("") { it.displayMessageBody ?: "" })
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, parts[0].timestampMillis)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        Log.w(TAG, "a text arrived while the helper held the SMS role; stored as $uri (not a seeded row, clear keeps it)")
    }
}

/** An MMS notification is not downloaded by the helper; the log line says so, so the window can be checked. */
class WapPushDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "an MMS notification arrived while the helper held the SMS role; not downloaded")
    }
}

/** Quick replies from the call screen: the helper sends nothing. */
class RespondViaMessageService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.w(TAG, "respond-via-message ignored: the helper sends nothing")
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

internal const val TAG = "smsseed"
