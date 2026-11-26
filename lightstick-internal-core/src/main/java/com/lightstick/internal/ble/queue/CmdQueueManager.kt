package com.lightstick.internal.ble.queue

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * - Abort notification: a command that will never complete — dropped on overflow, cleared on
 *   disconnect, or timed out by the watchdog — gets its `onAbort` called exactly once, so callers
 *   waiting for a result (reads, CCCD writes, OTA writes) fail instead of hanging forever.
 * - Watchdog: if an in-flight command does not call [signalComplete] within
 *   [commandTimeoutMs], it is aborted with [CommandAbortedException.Reason.TIMEOUT] and the queue
 *   moves on, so one lost GATT callback cannot stall the device's queue.
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
    private val maxQueueSizeProvider: (() -> Int)? = null,
    /** Max time a started command may take before the watchdog aborts it (`0` = no watchdog). */
    private val commandTimeoutMs: Long = CommandInterval.COMMAND_TIMEOUT_MS,
    /** Timer/clock abstraction; the default runs on the main looper. Replaced in unit tests. */
    private val scheduler: Scheduler = MainLooperScheduler()
) {
    /** Minimal timer used by the queue, so the scheduling logic can be tested on the JVM. */
    interface Scheduler {
        fun post(r: Runnable, delayMs: Long)
        fun cancel(r: Runnable)
        fun nowMs(): Long
    }

    private class MainLooperScheduler : Scheduler {
        private val handler = Handler(Looper.getMainLooper())
        override fun post(r: Runnable, delayMs: Long) {
            if (delayMs > 0) handler.postDelayed(r, delayMs) else handler.post(r)
        }
        override fun cancel(r: Runnable) = handler.removeCallbacks(r)
        override fun nowMs(): Long = SystemClock.elapsedRealtime()
    }

    private class Cmd(
        val op: String,
        val key: String?,
        val replace: Boolean,
        /** If true, this command does not wait for the minimum interval (see [enqueue]). */
        val bypassMinInterval: Boolean,
        val runner: () -> Unit,
        private val onAbort: ((CommandAbortedException) -> Unit)?
    ) {
        @Volatile private var aborted = false

        /** Calls `onAbort` at most once; exceptions from the callback are logged, not rethrown. */
        fun abort(e: CommandAbortedException) {
            if (aborted) return
            aborted = true
            try {
                onAbort?.invoke(e)
            } catch (t: Throwable) {
                Log.w("[CmdQueue] onAbort of '$op' threw: ${t.message}", t)
            }
        }
    }

    private val queues = ConcurrentHashMap<String, PendingCommandQueue<Cmd>>()
    private val running = ConcurrentHashMap<String, Boolean>()
    private val disconnected = ConcurrentHashMap<String, Boolean>()
    private val lastDone = ConcurrentHashMap<String, Long>()
    private val scheduled = ConcurrentHashMap<String, Runnable>()
    private val inFlight = ConcurrentHashMap<String, Cmd>()
    private val watchdogs = ConcurrentHashMap<String, Runnable>()
    private val timedOut = ConcurrentHashMap<String, Long>()

    /**
     * Clears the pending queue and scheduled task for [address]. Call on disconnect/cleanup.
     * The in-flight command and every pending command are aborted with
     * [CommandAbortedException.Reason.DISCONNECTED].
     */
    fun clear(address: String) {
        disconnected[address] = true
        scheduled.remove(address)?.let { scheduler.cancel(it) }
        watchdogs.remove(address)?.let { scheduler.cancel(it) }
        running[address] = false

        val aborted = buildList {
            inFlight.remove(address)?.let { add(it) }
            queues[address]?.drainAll()?.let { addAll(it) }
        }
        aborted.forEach {
            it.abort(CommandAbortedException(CommandAbortedException.Reason.DISCONNECTED, it.op, address))
        }
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
     * @param onAbort Called at most once if this command will never complete normally: the queue
     *                is already closed for [address], it is dropped on overflow, it is cleared on
     *                disconnect, or it times out. Not called for commands replaced by coalescing.
     * @param runner Async starter that MUST eventually call [signalComplete] with the same address.
     */
    fun enqueue(
        address: String,
        operation: String,
        coalesceKey: String? = null,
        replaceIfSameKey: Boolean = false,
        bypassMinInterval: Boolean = false,
        onAbort: ((CommandAbortedException) -> Unit)? = null,
        runner: () -> Unit
    ) {
        val cmd = Cmd(operation, coalesceKey, replaceIfSameKey, bypassMinInterval, runner, onAbort)

        if (disconnected[address] == true) {
            cmd.abort(CommandAbortedException(CommandAbortedException.Reason.DISCONNECTED, operation, address))
            return
        }

        val q = queues.getOrPut(address) { PendingCommandQueue() }

        // coalescing (keep only the latest pending with the same key) + overflow trimming
        val max = (maxQueueSizeProvider?.invoke() ?: config.maxQueueSizePerAddress).coerceAtLeast(1)
        val discarded = q.offer(cmd, coalesceKey, replaceIfSameKey, max, config.overflowPolicy)
        // Discarded commands never run: fail their callers instead of leaving them waiting.
        discarded.forEach {
            Log.w("[CmdQueue] $address overflow: dropped pending '${it.op}' " +
                "(policy=${config.overflowPolicy}, max=$max, totalDropped=${q.dropped})")
            it.abort(CommandAbortedException(CommandAbortedException.Reason.QUEUE_OVERFLOW, it.op, address))
        }

        // schedule next if not running
        if (running[address] != true) scheduleNext(address, waitFor(address))
    }

    /** Snapshot of the queue counters for [address] (all zero if nothing was ever enqueued). */
    fun stats(address: String): CommandQueueSnapshot {
        val q = queues[address] ?: return CommandQueueSnapshot(0, 0, 0, 0, 0, 0)
        return CommandQueueSnapshot(q.size, q.peak, q.dropped, q.replaced, q.executed, timedOut[address] ?: 0)
    }

    /** Zeroes the counters of [address] (the pending commands themselves are untouched). */
    fun resetStats(address: String) {
        queues[address]?.resetStats()
        timedOut.remove(address)
    }

    /**
     * Signals that the current command for [address] has completed.
     */
    fun signalComplete(address: String) {
        if (disconnected[address] == true) return
        watchdogs.remove(address)?.let { scheduler.cancel(it) }
        inFlight.remove(address)
        running[address] = false
        lastDone[address] = scheduler.nowMs()
        scheduleNext(address, waitFor(address))
    }

    // --- internals ---

    private fun waitFor(address: String): Long {
        // The next command opted out of the minimum interval (e.g. an OTA write): no extra wait.
        if (queues[address]?.head?.bypassMinInterval == true) return 0L
        val min = (minIntervalProvider?.invoke() ?: config.minIntervalMs).coerceAtLeast(0)
        if (min == 0L) return 0L
        val last = lastDone[address] ?: return 0L
        return (min - (scheduler.nowMs() - last)).coerceAtLeast(0)
    }

    private fun scheduleNext(address: String, delay: Long) {
        scheduled.remove(address)?.let { scheduler.cancel(it) }
        val r = Runnable { drain(address) }
        scheduled[address] = r
        scheduler.post(r, delay)
    }

    private fun drain(address: String) {
        if (disconnected[address] == true) return
        if (running[address] == true) return
        val q = queues[address] ?: return

        val next = q.pollFirst() ?: run {
            scheduled.remove(address)?.let { scheduler.cancel(it) }
            return
        }
        running[address] = true
        inFlight[address] = next
        // Armed before the runner: a runner that fails synchronously calls signalComplete(),
        // which disarms it again.
        armWatchdog(address, next)
        next.runner.invoke()
    }

    private fun armWatchdog(address: String, cmd: Cmd) {
        watchdogs.remove(address)?.let { scheduler.cancel(it) }
        if (commandTimeoutMs <= 0) return
        val w = Runnable { onWatchdog(address, cmd) }
        watchdogs[address] = w
        scheduler.post(w, commandTimeoutMs)
    }

    private fun onWatchdog(address: String, cmd: Cmd) {
        // Only act if this exact command is still the one in flight.
        if (!inFlight.remove(address, cmd)) return
        watchdogs.remove(address)
        timedOut.merge(address, 1L, Long::plus)
        Log.w("[CmdQueue] $address '${cmd.op}' got no GATT callback within ${commandTimeoutMs}ms — " +
            "aborting it and moving on")
        cmd.abort(CommandAbortedException(CommandAbortedException.Reason.TIMEOUT, cmd.op, address))
        signalComplete(address)
    }
}
