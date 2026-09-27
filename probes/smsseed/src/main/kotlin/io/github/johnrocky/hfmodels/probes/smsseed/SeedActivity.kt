package io.github.johnrocky.hfmodels.probes.smsseed

import android.app.Activity
import android.app.role.RoleManager
import android.content.ContentUris
import android.content.ContentValues
import android.os.Bundle
import android.os.SystemClock
import android.provider.Telephony
import android.util.Log
import io.github.johnrocky.hfmodels.samples.decide.sms.SmsGenerator
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Seeds or clears synthetic texts in the phone's SMS store. Only the default SMS app may write there,
 * so the SMS role is given to this app first and handed back afterwards (README.md):
 *
 *   adb shell am start -n io.github.johnrocky.hfmodels.probes.smsseed/.SeedActivity --ei count 300 --el seed 7
 *   adb shell am start -n io.github.johnrocky.hfmodels.probes.smsseed/.SeedActivity --ez clear true
 *
 * A seed inserts `count` unread inbox texts from `SmsGenerator` (samples/decide/src/sms; the same seed
 * gives the same texts, spread over the last 14 days) and keeps their `_id`s; a clear deletes exactly
 * those rows and nothing else. Each run writes `seed-result.json` to the app's external files dir and logs a
 * `RESULT` line under tag `smsseed`.
 */
class SeedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clear = intent.getBooleanExtra("clear", false)
        val count = intent.getIntExtra("count", 300)
        val seed = intent.getLongExtra("seed", 7L)
        Thread {
            val result = try {
                check(getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)) {
                    "this app does not hold the SMS role; take it first, see README.md"
                }
                if (clear) clear() else seed(count, seed)
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: e.toString())
            }
            result.put("action", if (clear) "clear" else "seed").put("date", Instant.now().toString())
            File(getExternalFilesDir(null), "seed-result.json").writeText(result.toString(2))
            Log.i(TAG, "RESULT $result")
            runOnUiThread { finish() }
        }.start()
    }

    private fun seed(count: Int, seed: Long): JSONObject {
        val start = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val texts = SmsGenerator.generate(count, seed)
        val ids = ArrayList<Long>(texts.size)
        for (m in texts) {
            val date = now - m.minutesAgo * 60_000L
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, m.from)
                put(Telephony.Sms.BODY, m.text)
                put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.DATE_SENT, date)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            val uri = contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            val id = uri?.let { ContentUris.parseId(it) } ?: -1L
            // A provider that ignores the write (the caller is not the default SMS app) answers with id 0.
            check(id > 0) { "the SMS provider did not insert (answer: $uri) after ${ids.size} rows" }
            ids += id
            remember(id)
        }
        return JSONObject()
            .put("count", ids.size).put("seed", seed)
            .put("first_id", ids.minOrNull()).put("last_id", ids.maxOrNull())
            .put("elapsed_ms", SystemClock.elapsedRealtime() - start)
            // What the generator wrote each text as; not a model answer.
            .put("written_as", JSONObject(texts.groupingBy { it.kind }.eachCount()))
    }

    private fun clear(): JSONObject {
        val start = SystemClock.elapsedRealtime()
        val ids = remembered()
        var deleted = 0
        for (chunk in ids.chunked(200)) {
            deleted += contentResolver.delete(Telephony.Sms.CONTENT_URI, "_id IN (${chunk.joinToString(",")})", null)
        }
        prefs().edit().remove(KEY_IDS).commit()
        return JSONObject().put("recorded", ids.size).put("deleted", deleted)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - start)
    }

    private fun prefs() = getSharedPreferences("seeded", MODE_PRIVATE)

    /** The ids this app inserted, in insertion order, across seeds until the next clear. */
    private fun remembered(): List<Long> =
        prefs().getString(KEY_IDS, "")!!.split(',').filter { it.isNotEmpty() }.map { it.toLong() }

    // Written after every row, so a seed that stops half way can still be cleared.
    private fun remember(id: Long) {
        val old = prefs().getString(KEY_IDS, "")!!
        prefs().edit().putString(KEY_IDS, if (old.isEmpty()) "$id" else "$old,$id").commit()
    }

    private companion object {
        const val KEY_IDS = "ids"
    }
}
