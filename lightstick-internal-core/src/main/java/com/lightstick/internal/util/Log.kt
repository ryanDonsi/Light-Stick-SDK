package com.lightstick.internal.util

import android.util.Log as AndroidLog

/**
 * Small logging facade to allow swapping out or muting logs from a single place.
 *
 * @since 1.0.0
 */
internal object Log {
    var enabled: Boolean = true
    var tag: String = "Lightstick"

    fun d(msg: String) { if (enabled) AndroidLog.d(tag, msg) }
    fun i(msg: String) { if (enabled) AndroidLog.i(tag, msg) }
    fun w(msg: String, t: Throwable? = null) { if (enabled) AndroidLog.w(tag, msg, t) }
    fun e(msg: String, t: Throwable? = null) { if (enabled) AndroidLog.e(tag, msg, t) }
}
