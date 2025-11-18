package com.lightstick.internal.ble.queue

import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
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
    private var config: CmdQueueConfig = CmdQueueConfig()
) {
    private data class Cmd(
        val op: String,
        val key: String?,
        val replace: Boolean,
        val runner: () -> Unit
    )

    private val handler = Handler(Looper.getMainLooper())
    private val queues = ConcurrentHashMap<String, ArrayDeque<Cmd>>()
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
     * @param runner Async starter that MUST eventually call [signalComplete] with the same address.
     */
    fun enqueue(
        address: String,
        operation: String,
        coalesceKey: String? = null,
        replaceIfSameKey: Boolean = false,
        runner: () -> Unit
    ) {
        if (disconnected[address] == true) return

        val q = queues.getOrPut(address) { ArrayDeque() }

        // coalescing: keep only the latest pending with the same key
        if (replaceIfSameKey && coalesceKey != null && q.isNotEmpty()) {
            val it = q.iterator()
            while (it.hasNext()) if (it.next().key == coalesceKey) it.remove()
        }

        q.addLast(Cmd(operation, coalesceKey, replaceIfSameKey, runner))

        // overflow trimming
        val max = config.maxQueueSizePerAddress.coerceAtLeast(1)
        while (q.size > max) {
            when (config.overflowPolicy) {
                OverflowPolicy.DROP_OLDEST -> if (q.isNotEmpty()) q.removeFirst()
                OverflowPolicy.DROP_NEWEST -> if (q.isNotEmpty()) q.removeLast()
            }
        }

        // schedule next if not running
        if (running[address] != true) scheduleNext(address, waitFor(address))
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
        val min = config.minIntervalMs.coerceAtLeast(0)
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
