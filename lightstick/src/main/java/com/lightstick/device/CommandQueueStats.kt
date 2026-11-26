package com.lightstick.device

/**
 * Counters of one device's GATT command queue, for tuning `LSBluetooth.setCommandIntervalMs` and
 * `LSBluetooth.setCommandQueueSize` (see [Device.getCommandQueueStats]).
 *
 * The queue is shared by **every** GATT operation of the device (FF02 frames, reads, CCCD writes,
 * FF03 / FF0A, OTA writes). The counters run from the connection (or the last
 * [Device.resetCommandQueueStats]) until now.
 *
 * @property pending Commands waiting right now (the one in flight is not counted).
 * @property peakPending Largest [pending] reached. A value equal to the configured queue size
 *           means the queue was full at some point.
 * @property dropped Commands discarded because the queue overflowed — they were **never executed**
 *           and got no completion callback. Non-zero means frames/operations were lost: raise the
 *           queue size, raise the send interval's headroom, or send less.
 * @property replaced Pending commands replaced by a newer one with the same coalesce key
 *           (single effect / color sends; timeline frames are never replaced).
 * @property executed Commands handed to the BLE stack for execution.
 *
 * @since 2.200.0
 */
data class CommandQueueStats(
    val pending: Int,
    val peakPending: Int,
    val dropped: Long,
    val replaced: Long,
    val executed: Long
)
