package com.lightstick.internal.ble

import com.lightstick.internal.ble.queue.CmdQueueConfig
import com.lightstick.internal.ble.queue.CmdQueueManager
import com.lightstick.internal.ble.queue.CommandAbortedException
import com.lightstick.internal.ble.queue.CommandAbortedException.Reason
import com.lightstick.internal.ble.queue.OverflowPolicy
import org.junit.Assert.*
import org.junit.Test

/** JVM tests of the queue's abort and watchdog behaviour, driven by a fake clock. */
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
        intervalMs: Long = 0,
        timeoutMs: Long = 3_000
    ) = CmdQueueManager(
        CmdQueueConfig(minIntervalMs = intervalMs, maxQueueSizePerAddress = size,
            overflowPolicy = OverflowPolicy.DROP_OLDEST),
        commandTimeoutMs = timeoutMs,
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
        // No watchdog fires for the cleared in-flight command.
        assertEquals(3, aborts.size)
    }

    @Test fun watchdogAbortsAStuckCommandAndRunsTheNextOne() {
        val s = FakeScheduler()
        val q = queue(s, timeoutMs = 3_000)
        var abort: CommandAbortedException? = null
        var secondRan = false
        q.enqueue(addr, "stuck", onAbort = { abort = it }) { /* callback never comes */ }
        q.enqueue(addr, "next") { secondRan = true }

        s.advance(2_999)
        assertNull(abort)
        assertFalse(secondRan)

        s.advance(1)
        assertEquals(Reason.TIMEOUT, abort?.reason)
        assertEquals("stuck", abort?.operation)
        assertTrue(secondRan)
        assertEquals(1L, q.stats(addr).timedOut)
    }

    @Test fun completionDisarmsTheWatchdog() {
        val s = FakeScheduler()
        val q = queue(s, timeoutMs = 3_000)
        var aborted = false
        q.enqueue(addr, "ok", onAbort = { aborted = true }) { q.signalComplete(addr) }
        s.advance(10_000)
        assertFalse(aborted)
        assertEquals(0L, q.stats(addr).timedOut)
        assertEquals(1L, q.stats(addr).executed)
    }

    @Test fun abortIsDeliveredOnlyOnce() {
        val s = FakeScheduler()
        val q = queue(s, timeoutMs = 100)
        var count = 0
        q.enqueue(addr, "x", onAbort = { count++ }) {}
        s.advance(100)          // timeout aborts it
        q.clear(addr)           // nothing left to abort
        assertEquals(1, count)
    }

    @Test fun minIntervalStillAppliesBetweenCommands() {
        val s = FakeScheduler()
        val q = queue(s, intervalMs = 50)
        val startedAt = mutableListOf<Long>()
        repeat(3) { q.enqueue(addr, "w$it") { startedAt += s.now; q.signalComplete(addr) } }
        s.advance(1_000)
        assertEquals(listOf(0L, 50L, 100L), startedAt)
    }

    @Test fun zeroTimeoutDisablesTheWatchdog() {
        val s = FakeScheduler()
        val q = queue(s, timeoutMs = 0)
        var aborted = false
        q.enqueue(addr, "stuck", onAbort = { aborted = true }) {}
        s.advance(60_000)
        assertFalse(aborted)
    }

    /**
     * Timeline-style traffic: a frame every 10 ms, each write taking 7 ms, coalesced under one key.
     * With a 50 ms interval the newest frame must go out within one interval of being queued, and
     * nothing is dropped (older pending frames are replaced instead).
     */
    @Test fun coalescedFramesStayWithinOneIntervalOfRealTime() {
        val s = FakeScheduler()
        val q = queue(s, size = 8, intervalMs = 50)
        val sent = mutableListOf<Pair<Int, Long>>()   // frame number -> time it started
        for (frame in 0 until 100) {
            s.now = frame * 10L
            q.enqueue(addr, "f$frame", coalesceKey = "TL", replaceIfSameKey = true) {
                sent += frame to s.now
                s.post(Runnable { q.signalComplete(addr) }, 7)
            }
            s.advance(0)
            s.advance(9)
        }
        s.advance(1_000)

        val stats = q.stats(addr)
        assertEquals(0L, stats.dropped)
        assertTrue(stats.replaced > 0)
        // Lag = start time - time the frame was produced.
        val maxLag = sent.maxOf { (frame, at) -> at - frame * 10L }
        assertTrue("max lag $maxLag ms", maxLag <= 50 + 7)
        // The last frame produced is the last one sent.
        assertEquals(99, sent.last().first)
    }
}
