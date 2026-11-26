package com.lightstick.internal.ble.queue

/**
 * Point-in-time view of one device's GATT command queue (see `Device.getCommandQueueStats`).
 * Public only so the `lightstick` module can map it; not part of the SDK API.
 */
data class CommandQueueSnapshot(
    val pending: Int,
    val peakPending: Int,
    val dropped: Long,
    val replaced: Long,
    val executed: Long,
    /** Commands aborted by the watchdog because no GATT callback arrived in time. */
    val timedOut: Long = 0
)
