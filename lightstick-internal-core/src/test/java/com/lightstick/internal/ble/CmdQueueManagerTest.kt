package com.lightstick.internal.ble

import com.lightstick.internal.ble.queue.CmdQueueConfig
import com.lightstick.internal.ble.queue.CmdQueueManager
import com.lightstick.internal.ble.queue.CommandAbortedException.Reason
import com.lightstick.internal.ble.queue.OverflowPolicy
import org.junit.Assert.*
import org.junit.Test

/** JVM tests of the queue's abort behaviour, driven by a fake clock. */
class CmdQueueManagerTest {

    private class FakeScheduler : CmdQueueManager.Scheduler {
        private class Task(val at: Long, val r: Runnable)
        private val tasks = mutableListOf<Task>()
        var now = 0L

        override fun post(r: Runnable, delayMs: Long) { tasks += Task(now + delayMs.coerceAtLeast(0), r) }
        override fun cancel(r: Runnable) { tasks.removeAll { it.r === r } }
        override fun nowMs(): Long = now

        /** Runs every task due within [ms], in time order (tasks may post more tasks). */
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.filter { it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(next)
                now = maxOf(now, next.at)
                next.r.run()
            }
            now = end
        }
    }

    private val addr = "AA:BB:CC:DD:EE:FF"

    private fun queue(
        sched: FakeScheduler,
        size: Int = 8,
        intervalMs: Long = 0
    ) = CmdQueueManager(
        CmdQueueConfig(minIntervalMs = intervalMs, maxQueueSizePerAddress = size,
            overflowPolicy = OverflowPolicy.DROP_OLDEST),
        scheduler = sched
    )

    @Test fun overflowFailsTheDroppedCommand() {
        val s = FakeScheduler()
        val q = queue(s, size = 1)
        val aborts = mutableListOf<Pair<String, Reason>>()
        // "a" starts running (nothing completes it); "b" waits; "c" pushes "b" out.
        q.enqueue(addr, "a", onAbort = { aborts += "a" to it.reason }) {}
        s.advance(0)
        q.enqueue(addr, "b", onAbort = { aborts += "b" to it.reason }) {}
        q.enqueue(addr, "c", onAbort = { aborts += "c" to it.reason }) {}
        assertEquals(listOf("b" to Reason.QUEUE_OVERFLOW), aborts)
        assertEquals(1L, q.stats(addr).dropped)
    }

    @Test fun clearFailsInFlightAndPendingAndLaterEnqueues() {
        val s = FakeScheduler()
        val q = queue(s)
        val aborts = mutableListOf<Pair<String, Reason>>()
        q.enqueue(addr, "running", onAbort = { aborts += "running" to it.reason }) {}
        s.advance(0)
        q.enqueue(addr, "waiting", onAbort = { aborts += "waiting" to it.reason }) {}

        q.clear(addr)
        assertEquals(listOf("running" to Reason.DISCONNECTED, "waiting" to Reason.DISCONNECTED), aborts)

        var ran = false
        q.enqueue(addr, "late", onAbort = { aborts += "late" to it.reason }) { ran = true }
        s.advance(10_000)
        assertFalse(ran)
        assertEquals("late" to Reason.DISCONNECTED, aborts.last())
        assertEquals(3, aborts.size)
    }

    @Test fun minIntervalStillAppliesBetweenCommands() {
        val s = FakeScheduler()
        val q = queue(s, intervalMs = 50)
        val startedAt = mutableListOf<Long>()
        repeat(3) { q.enqueue(addr, "w$it") { startedAt += s.now; q.signalComplete(addr) } }
        s.advance(1_000)
        assertEquals(listOf(0L, 50L, 100L), startedAt)
    }

}
