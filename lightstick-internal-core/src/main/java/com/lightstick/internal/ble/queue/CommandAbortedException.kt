package com.lightstick.internal.ble.queue

import androidx.annotation.RestrictTo

/**
 * A queued GATT command that will never complete normally. Delivered as `Result.failure` to the
 * command's result callback (reads, notification setup, OTA writes) so callers fail fast instead of
 * waiting forever.
 *
 * Extends [IllegalStateException], so existing `catch (e: IllegalStateException)` code keeps working.
 * Public only because it reaches app callbacks inside `Result.failure`; match on [reason] if needed.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class CommandAbortedException(
    val reason: Reason,
    val operation: String,
    val address: String
) : IllegalStateException("GATT command '$operation' on $address aborted: $reason") {

    enum class Reason {
        /** Discarded because the device's command queue was full (see `setCommandQueueSize`). */
        QUEUE_OVERFLOW,

        /** The device disconnected (or was disconnected) before the command completed. */
        DISCONNECTED,

        /** No GATT callback arrived within [CommandInterval.COMMAND_TIMEOUT_MS]. */
        TIMEOUT
    }
}
