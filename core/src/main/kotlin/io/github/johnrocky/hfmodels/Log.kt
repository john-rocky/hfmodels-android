package io.github.johnrocky.hfmodels

import android.util.Log

/** Logging seam so JVM unit tests can capture lines without Android's Log. */
interface HfLog {
    fun i(msg: String)
    fun w(msg: String, t: Throwable? = null)
    fun e(msg: String, t: Throwable? = null)

    companion object {
        const val TAG = "hfmodels"
    }
}

object AndroidHfLog : HfLog {
    override fun i(msg: String) { Log.i(HfLog.TAG, msg) }
    override fun w(msg: String, t: Throwable?) { Log.w(HfLog.TAG, msg, t) }
    override fun e(msg: String, t: Throwable?) { Log.e(HfLog.TAG, msg, t) }
}
