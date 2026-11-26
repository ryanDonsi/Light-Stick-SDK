package com.lightstick.internal.util

import android.util.Log as AndroidLog

/**
 * Small logging facade to allow swapping out or muting logs from a single place.
 *
 * @since 1.0.0
 */
internal object Log {
    @Volatile var enabled: Boolean = false
    @Volatile var tag: String = "Lightstick"

    fun d(msg: String) { if (enabled) AndroidLog.d(tag, msg) }
    fun i(msg: String) { if (enabled) AndroidLog.i(tag, msg) }
    fun w(msg: String, t: Throwable? = null) { if (enabled) AndroidLog.w(tag, msg, t) }
    fun e(msg: String, t: Throwable? = null) { if (enabled) AndroidLog.e(tag, msg, t) }

    /**
     * Lazy variant for hot paths (every GATT write / timeline frame): [msg] — string templates,
     * hex dumps — is only built when logging is enabled, so a disabled log costs nothing.
     */
    inline fun d(msg: () -> String) { if (enabled) AndroidLog.d(tag, msg()) }
}
