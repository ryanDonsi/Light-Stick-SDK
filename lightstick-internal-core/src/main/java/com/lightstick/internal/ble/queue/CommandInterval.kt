package com.lightstick.internal.ble.queue

/**
 * Minimum interval between completed GATT commands per device (applies to the whole per-device
 * serialized command queue — FF02 effect writes and game/control commands. Reads, CCCD writes and
 * OTA firmware writes are exempt: they pace themselves by waiting for the device's response/ack).
 *
 * Public only so the `lightstick` module and unit tests can reach it; not part of the SDK API.
 */
object CommandInterval {
    /**
     * Max time one started GATT command may wait for its callback before the queue's watchdog
     * aborts it and moves on (a lost callback would otherwise stall the device's queue for good).
     * Generous on purpose: a healthy read/write completes within a few connection intervals.
     */
    const val COMMAND_TIMEOUT_MS: Long = 3_000L

    /** Default minimum interval in milliseconds. */
    const val DEFAULT_MS: Long = 50L

    /** Upper bound accepted by [validate]. */
    const val MAX_MS: Long = 1000L

    /**
     * @return [intervalMs] if it is within `0..[MAX_MS]` (0 = no minimum interval).
     * @throws IllegalArgumentException otherwise.
     */
    @JvmStatic
    fun validate(intervalMs: Long): Long {
        require(intervalMs in 0L..MAX_MS) { "commandIntervalMs must be within 0..$MAX_MS (got $intervalMs)" }
        return intervalMs
    }

    /** Default maximum number of pending commands per device queue. */
    const val DEFAULT_QUEUE_SIZE: Int = 8

    /** Upper bound accepted by [validateQueueSize]. */
    const val MAX_QUEUE_SIZE: Int = 256

    /**
     * @return [size] if it is within `1..[MAX_QUEUE_SIZE]`.
     * @throws IllegalArgumentException otherwise.
     */
    @JvmStatic
    fun validateQueueSize(size: Int): Int {
        require(size in 1..MAX_QUEUE_SIZE) { "commandQueueSize must be within 1..$MAX_QUEUE_SIZE (got $size)" }
        return size
    }
}
