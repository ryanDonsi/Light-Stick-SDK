package com.lightstick.internal.ble.queue

/**
 * Configuration for a per-address serialized command queue.
 *
 * @property minIntervalMs        Minimum interval in milliseconds between completed commands per address.
 * @property maxQueueSizePerAddress Maximum number of pending commands per address (>=1).
 * @property overflowPolicy       Policy to apply when the queue exceeds its capacity.
 */
internal data class CmdQueueConfig(
    val minIntervalMs: Long = 0L,
    val maxQueueSizePerAddress: Int = 64,
    val overflowPolicy: OverflowPolicy = OverflowPolicy.DROP_OLDEST
)

/**
 * Policy to apply on queue overflow.
 */
internal enum class OverflowPolicy {
    /** Remove the oldest pending entry first (default). */
    DROP_OLDEST,

    /** Remove the newest pending entry first (optional parity option). */
    DROP_NEWEST
}
