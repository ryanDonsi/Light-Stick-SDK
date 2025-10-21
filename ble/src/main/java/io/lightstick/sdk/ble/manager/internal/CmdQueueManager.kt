package io.lightstick.sdk.ble.manager.internal

import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-address **serialized** command queue for GATT operations.
 *
 * Features:
 * - Ensures **one in-flight command** per address (FIFO by default).
 * - Supports a **minimum interval** between completed commands (rate limiting).
 * - Supports **maximum pending size** (back-pressure).
 * - Supports **coalescing**: with the same [coalesceKey] and `replaceIfSameKey=true`,
 *   removes older **pending** commands that share the key, keeping the latest one.
 *
 * Execution model:
 * - [enqueue] only schedules execution and returns immediately.
 * - The queue invokes your `runner()` to start an **async** BLE op.
 * - When that op finishes (success/failure), you **must** call
 *   [signalCommandComplete] with the same address so the queue can proceed.
 *
 * Threading:
 * - Scheduling is done on the **main thread** via a [Handler].
 * - Call [enqueue], [clear] and [signalCommandComplete] from main thread for simplicity.
 *
 * @constructor
 * @param config Initial runtime configuration. You can later change it with [updateConfig].
 */
class CmdQueueManager(
    private var config: CmdQueueConfig = CmdQueueConfig()
) {
    private data class Cmd(
        val operation: String,
        val coalesceKey: String?,      // commands with the same key can be coalesced
        val replaceIfSameKey: Boolean, // if true, remove pending cmds with same key
        val runner: () -> Unit         // async BLE starter; completion -> signalCommandComplete()
    )

    private val handler = Handler(Looper.getMainLooper())

    // Per-address pending queue
    private val queues = ConcurrentHashMap<String, ArrayDeque<Cmd>>()
    // Whether the address has a command currently running
    private val running = ConcurrentHashMap<String, Boolean>()
    // Last completion timestamp per address (for rate-limiting)
    private val lastCompletedAt = ConcurrentHashMap<String, Long>()
    // Scheduled next runnable per address
    private val scheduledNext = ConcurrentHashMap<String, Runnable?>()

    /**
     * Clear pending state for [address]: removes all pending commands, cancels any scheduled
     * drain runnable, and flips the running flag to `false`. Call on disconnect/cleanup.
     *
     * @param address Device MAC address key.
     */
    fun clear(address: String) {
        queues[address]?.clear()
        scheduledNext.remove(address)?.let { handler.removeCallbacks(it) }
        running[address] = false
    }

    /**
     * Public convenience to configure queue without exposing internal types to app code.
     * (Used by a public wrapper like `BleGattManager.configureCommandQueue(...)`.)
     *
     * @param minIntervalMs Minimum interval between dequeues per address (ms).
     * @param maxQueueSize Maximum number of pending commands per address (>=1).
     *
     * @throws IllegalArgumentException if `maxQueueSize < 1` or `minIntervalMs < 0`.
     */
    @Synchronized
    fun configureCommandQueue(
        minIntervalMs: Long,
        maxQueueSize: Int
    ) {
        require(minIntervalMs >= 0) { "minIntervalMs must be >= 0" }
        require(maxQueueSize >= 1) { "maxQueueSize must be >= 1" }
        updateConfig(
            config.copy(
                minIntervalMs = minIntervalMs,
                maxQueueSizePerAddress = maxQueueSize,
                overflowPolicy = OverflowPolicy.DROP_OLDEST
            )
        )
    }

    /**
     * Enqueue a new command for [address].
     *
     * Order guarantees:
     * - Commands for the same [address] execute **one-by-one** in FIFO order,
     *   **except** when you explicitly coalesce (older *pending* ones with the same key are removed)
     *   or when overflow trimming drops oldest.
     *
     * Overflow:
     * - After enqueue, if pending size exceeds [CmdQueueConfig.maxQueueSizePerAddress],
     *   older **pending** commands are removed according to [CmdQueueConfig.overflowPolicy].
     *
     * Scheduling:
     * - If nothing is running for [address], the next drain is scheduled immediately
     *   (or after [CmdQueueConfig.minIntervalMs], measured from last completion).
     *
     * @param address Target device MAC address.
     * @param operation Label used for logs/diagnostics.
     * @param coalesceKey Logical key to group commands that can be coalesced (nullable).
     * @param replaceIfSameKey If `true`, remove older **pending** commands with the same [coalesceKey],
     * and keep only the newest command.
     * @param runner Async BLE starter. **Must** eventually lead to a call to [signalCommandComplete]
     * for the same [address] (e.g., in the GATT callback).
     */
    fun enqueue(
        address: String,
        operation: String,
        coalesceKey: String? = null,
        replaceIfSameKey: Boolean = false,
        runner: () -> Unit
    ) {
        val q = queues.getOrPut(address) { ArrayDeque() }

        // 1) Coalesce: drop older pending commands that share the same key.
        if (replaceIfSameKey && coalesceKey != null && q.isNotEmpty()) {
            val it = q.iterator()
            while (it.hasNext()) {
                val c = it.next()
                if (c.coalesceKey == coalesceKey) it.remove()
            }
        }

        // 2) Enqueue at tail.
        q.addLast(Cmd(operation, coalesceKey, replaceIfSameKey, runner))

        // 3) Overflow control: trim from head if pending exceeds max.
        val max = config.maxQueueSizePerAddress.coerceAtLeast(1)
        while (q.size > max) {
            when (config.overflowPolicy) {
                OverflowPolicy.DROP_OLDEST -> if (q.isNotEmpty()) q.removeFirst()
            }
        }

        // 4) If nothing is running, schedule next according to minIntervalMs.
        if (running[address] != true) {
            scheduleNext(address, delayMs = computeWait(address))
        }
    }

    /**
     * Notify the queue that the **current** command for [address] has completed
     * (either success or failure). This unlocks the next command.
     *
     * @param address Device MAC address whose in-flight op finished.
     */
    fun signalCommandComplete(address: String) {
        running[address] = false
        lastCompletedAt[address] = System.currentTimeMillis()
        scheduleNext(address, delayMs = computeWait(address))
    }

    // ---- Internal helpers ----------------------------------------------------

    /**
     * Atomically update the runtime configuration of this queue.
     *
     * @param newConfig New configuration to apply.
     */
    private fun updateConfig(newConfig: CmdQueueConfig) {
        config = newConfig
    }

    /** Compute how long to wait before starting the next command for [address]. */
    private fun computeWait(address: String): Long {
        val min = config.minIntervalMs.coerceAtLeast(0L)
        if (min == 0L) return 0L
        val last = lastCompletedAt[address] ?: return 0L
        val elapsed = System.currentTimeMillis() - last
        val remain = min - elapsed
        return if (remain > 0) remain else 0L
    }

    /** Cancel any prior schedule and schedule the next drain on the main thread. */
    private fun scheduleNext(address: String, delayMs: Long) {
        scheduledNext.remove(address)?.let { handler.removeCallbacks(it) }
        val r = Runnable { drain(address) }
        scheduledNext[address] = r
        if (delayMs > 0) handler.postDelayed(r, delayMs) else handler.post(r)
    }

    /** Pull the next pending command (if any) and start it. */
    private fun drain(address: String) {
        if (running[address] == true) return
        val q = queues[address] ?: return
        val next = if (q.isNotEmpty()) q.removeFirst() else run {
            scheduledNext.remove(address)?.let { handler.removeCallbacks(it) }
            return
        }
        running[address] = true
        next.runner.invoke() // async BLE call; completion -> signalCommandComplete(address)
    }
}
