package com.lightstick.internal.ble.queue

import java.util.ArrayDeque

/**
 * Pending-command buffer of one device queue: key-based replacement, bounded size with an
 * overflow policy, and the counters behind `Device.getCommandQueueStats()`.
 *
 * Pure Kotlin (no Android types) so it can be unit-tested on the JVM. Mutations must come from a
 * single thread (the queue manager's main thread); the counters are `@Volatile` so any thread can
 * read them. The currently running command is not part of the buffer (it was polled out).
 */
internal class PendingCommandQueue<T> {

    private class Entry<T>(val item: T, val key: String?)

    private val deque = ArrayDeque<Entry<T>>()

    /** Commands currently waiting (not counting the one in flight). */
    @Volatile var size: Int = 0
        private set

    /** Largest [size] reached since creation / the last [resetStats]. */
    @Volatile var peak: Int = 0
        private set

    /** Commands discarded by the overflow policy (never executed, no completion callback). */
    @Volatile var dropped: Long = 0
        private set

    /** Pending commands replaced by a newer one with the same coalesce key. */
    @Volatile var replaced: Long = 0
        private set

    /** Commands handed out for execution by [pollFirst]. */
    @Volatile var executed: Long = 0
        private set

    /**
     * Adds [item]. If [replaceIfSameKey] and [key] is set, older pending items with that key are
     * removed first (counted in [replaced]). Then, while more than [max] items wait, items are
     * discarded per [policy] (counted in [dropped]).
     *
     * @return the items discarded by the overflow policy (possibly including [item] itself under
     *         [OverflowPolicy.DROP_NEWEST]).
     */
    fun offer(item: T, key: String?, replaceIfSameKey: Boolean, max: Int, policy: OverflowPolicy): List<T> {
        if (replaceIfSameKey && key != null && deque.isNotEmpty()) {
            val it = deque.iterator()
            while (it.hasNext()) {
                if (it.next().key == key) {
                    it.remove()
                    replaced++
                }
            }
        }

        deque.addLast(Entry(item, key))

        val limit = max.coerceAtLeast(1)
        var discarded: MutableList<T>? = null
        while (deque.size > limit) {
            val e = when (policy) {
                OverflowPolicy.DROP_OLDEST -> deque.removeFirst()
                OverflowPolicy.DROP_NEWEST -> deque.removeLast()
            }
            dropped++
            (discarded ?: mutableListOf<T>().also { discarded = it }).add(e.item)
        }

        size = deque.size
        if (size > peak) peak = size
        return discarded ?: emptyList()
    }

    /** Removes and returns the next command to run, or `null` if none is waiting. */
    fun pollFirst(): T? {
        val e = deque.pollFirst() ?: return null
        executed++
        size = deque.size
        return e.item
    }

    /** Discards all pending items without counting them as dropped (disconnect / cleanup). */
    fun clear() {
        deque.clear()
        size = 0
    }

    /** Zeroes the counters; [peak] restarts from the current [size]. */
    fun resetStats() {
        peak = size
        dropped = 0
        replaced = 0
        executed = 0
    }
}
