package com.lightstick.internal.ble.queue

import android.os.Handler
import android.os.Looper
import com.lightstick.internal.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Serialized command queue per BLE address.
 *
 * Features:
 * - Single in-flight command per address.
 * - Optional coalescing: pending items with the same key can be replaced by the latest one.
 * - Rate limiting: enforces a minimum interval between completed commands per address.
 * - Overflow control: DROP_OLDEST or DROP_NEWEST pending entries when capacity is exceeded.
 *
 * Threading:
 * - Scheduling is done on the main thread via a [Handler].
 * - Call [enqueue], [signalComplete], and [clear] on the main thread for simplicity.
 */
internal class CmdQueueManager(
    private var config: CmdQueueConfig = CmdQueueConfig(),
    /** If set, read on every scheduling decision and overrides [CmdQueueConfig.minIntervalMs]. */
    private val minIntervalProvider: (() -> Long)? = null,
    /** If set, read on every enqueue and overrides [CmdQueueConfig.maxQueueSizePerAddress]. */
    private val maxQueueSizeProvider: (() -> Int)? = null
) {
    private data class Cmd(
        val op: String,
        val key: String?,
        val replace: Boolean,
        /** If true, this command does not wait for the minimum interval (see [enqueue]). */
        val bypassMinInterval: Boolean,
        val runner: () -> Unit
    )

    private val handler = Handler(Looper.getMainLooper())
    private val queues = ConcurrentHashMap<String, PendingCommandQueue<Cmd>>()
    private val running = ConcurrentHashMap<String, Boolean>()
    private val disconnected = ConcurrentHashMap<String, Boolean>()
    private val lastDone = ConcurrentHashMap<String, Long>()
    private val scheduled = ConcurrentHashMap<String, Runnable?>()

    /**
     * Clears the pending queue and scheduled task for [address]. Call on disconnect/cleanup.
     */
    fun clear(address: String) {
        disconnected[address] = true
        queues[address]?.clear()
        scheduled.remove(address)?.let { handler.removeCallbacks(it) }
        running[address] = false
    }

    /**
     * Enqueues a new command for [address].
     *
     * @param address BLE MAC address key.
     * @param operation Debug/diagnostic label.
     * @param coalesceKey If provided with [replaceIfSameKey]=true, removes older pending entries
     *                    that share the same key, keeping the newest one.
     * @param replaceIfSameKey Whether to coalesce by [coalesceKey].
     * @param bypassMinInterval If true, this command is started right away once the previous command
     *                    has completed, without waiting for the configured minimum interval. For
     *                    commands that pace themselves (OTA writes wait for each write ack), where the
     *                    interval — meant for effect frames — would only slow the transfer down.
     * @param runner Async starter that MUST eventually call [signalComplete] with the same address.
     */
    fun enqueue(
        address: String,
        operation: String,
        coalesceKey: String? = null,
        replaceIfSameKey: Boolean = false,
        bypassMinInterval: Boolean = false,
        runner: () -> Unit
    ) {
        if (disconnected[address] == true) return

        val q = queues.getOrPut(address) { PendingCommandQueue() }

        // coalescing (keep only the latest pending with the same key) + overflow trimming
        val max = (maxQueueSizeProvider?.invoke() ?: config.maxQueueSizePerAddress).coerceAtLeast(1)
        val discarded = q.offer(
            Cmd(operation, coalesceKey, replaceIfSameKey, bypassMinInterval, runner),
            coalesceKey, replaceIfSameKey, max, config.overflowPolicy
        )
        // Discarded commands never run and never signal completion — make that visible in the log.
        discarded.forEach {
            Log.w("[CmdQueue] $address overflow: dropped pending '${it.op}' " +
                "(policy=${config.overflowPolicy}, max=$max, totalDropped=${q.dropped})")
        }

        // schedule next if not running
        if (running[address] != true) scheduleNext(address, waitFor(address))
    }

    /** Snapshot of the queue counters for [address] (all zero if nothing was ever enqueued). */
    fun stats(address: String): CommandQueueSnapshot {
        val q = queues[address] ?: return CommandQueueSnapshot(0, 0, 0, 0, 0)
        return CommandQueueSnapshot(q.size, q.peak, q.dropped, q.replaced, q.executed)
    }

    /** Zeroes the counters of [address] (the pending commands themselves are untouched). */
    fun resetStats(address: String) {
        queues[address]?.resetStats()
    }

    /**
     * Signals that the current command for [address] has completed.
     */
    fun signalComplete(address: String) {
        if (disconnected[address] == true) return
        running[address] = false
        lastDone[address] = System.currentTimeMillis()
        scheduleNext(address, waitFor(address))
    }

    // --- internals ---

    private fun waitFor(address: String): Long {
        // The next command opted out of the minimum interval (e.g. an OTA write): no extra wait.
        if (queues[address]?.head?.bypassMinInterval == true) return 0L
        val min = (minIntervalProvider?.invoke() ?: config.minIntervalMs).coerceAtLeast(0)
        if (min == 0L) return 0L
        val last = lastDone[address] ?: return 0L
        return (min - (System.currentTimeMillis() - last)).coerceAtLeast(0)
    }

    private fun scheduleNext(address: String, delay: Long) {
        scheduled.remove(address)?.let { handler.removeCallbacks(it) }
        val r = Runnable { drain(address) }
        scheduled[address] = r
        if (delay > 0) handler.postDelayed(r, delay) else handler.post(r)
    }

    private fun drain(address: String) {
        if (disconnected[address] == true) return
        if (running[address] == true) return
        val q = queues[address] ?: return

        val next = q.pollFirst() ?: run {
            scheduled.remove(address)?.let { handler.removeCallbacks(it) }
            return
        }
        running[address] = true
        next.runner.invoke()
    }
}
