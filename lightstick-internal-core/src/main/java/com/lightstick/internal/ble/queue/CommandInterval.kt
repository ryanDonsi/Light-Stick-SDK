package com.lightstick.internal.ble.queue

/**
 * Minimum interval between completed GATT commands per device (applies to the whole per-device
 * serialized command queue — FF02 effect writes, but also reads, CCCD writes and OTA writes).
 *
 * Public only so the `lightstick` module and unit tests can reach it; not part of the SDK API.
 */
object CommandInterval {
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
}
