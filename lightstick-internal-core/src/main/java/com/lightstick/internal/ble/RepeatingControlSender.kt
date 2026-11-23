package com.lightstick.internal.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sends a Control command, then repeats it for loss protection (BLE protocol v2.2 §2.6/§4.7:
 * broadcasts are not acknowledged, and commands are idempotent, so the app may resend).
 *
 * The first transmission happens immediately on the caller's thread; the remaining
 * ([repeat] - 1) transmissions follow every [intervalMs] ms on [scope]. [send] is expected to use
 * a fresh effectIndex per call (see [ControlManager.send]) so repeats are not dropped as
 * duplicates. At most one repeat sequence is pending: starting a new one, or calling
 * [cancelPending], drops the previous one — callers must cancel whenever a repeated command
 * would become wrong (e.g. a late RESUME copy would release a newer PAUSE).
 *
 * Pure Kotlin / coroutines only so it can be unit-tested on the JVM. Repeat count and interval
 * are internal tuning values (not public API).
 */
internal class RepeatingControlSender(
    private val scope: CoroutineScope,
    private val send: (cmdIndex: Int, groupMask: Long) -> Boolean,
    private val repeat: Int = DEFAULT_REPEAT,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS
) {
    companion object {
        /** Total transmissions per command (first + repeats). */
        const val DEFAULT_REPEAT = 3
        /** Gap between transmissions; longer than the relay's ~60 ms copy burst. */
        const val DEFAULT_INTERVAL_MS = 100L
    }

    init {
        require(repeat >= 1) { "repeat must be >= 1" }
        require(intervalMs >= 0) { "intervalMs must be >= 0" }
    }

    @Volatile private var pending: Job? = null

    /**
     * Sends [cmdIndex] now and schedules the remaining repeats.
     * @return the result of the first transmission; repeats are not started if it failed.
     */
    @Synchronized
    fun sendRepeated(cmdIndex: Int, groupMask: Long): Boolean {
        cancelPending()
        val ok = send(cmdIndex, groupMask)
        if (!ok || repeat == 1) return ok
        pending = scope.launch {
            repeat(repeat - 1) {
                delay(intervalMs)
                if (!isActive) return@launch
                if (!send(cmdIndex, groupMask)) return@launch
            }
        }
        return ok
    }

    /** Drops any repeats that have not been sent yet. */
    @Synchronized
    fun cancelPending() {
        pending?.cancel()
        pending = null
    }
}
