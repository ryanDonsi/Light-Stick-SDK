package com.lightstick.internal.ble

import com.lightstick.internal.ble.queue.OverflowPolicy
import com.lightstick.internal.ble.queue.PendingCommandQueue
import org.junit.Assert.*
import org.junit.Test

class PendingCommandQueueTest {

    private fun q() = PendingCommandQueue<String>()

    @Test fun offerAndPollKeepFifoOrderAndCountExecuted() {
        val q = q()
        q.offer("a", null, false, 8, OverflowPolicy.DROP_OLDEST)
        q.offer("b", null, false, 8, OverflowPolicy.DROP_OLDEST)
        assertEquals(2, q.size)
        assertEquals("a", q.pollFirst())
        assertEquals("b", q.pollFirst())
        assertNull(q.pollFirst())
        assertEquals(2L, q.executed)
        assertEquals(0, q.size)
        assertEquals(2, q.peak)
    }

    @Test fun overflowDropsOldestAndCountsThem() {
        val q = q()
        val dropped = mutableListOf<String>()
        listOf("1", "2", "3", "4", "5").forEach {
            dropped += q.offer(it, null, false, 3, OverflowPolicy.DROP_OLDEST)
        }
        assertEquals(listOf("1", "2"), dropped)
        assertEquals(2L, q.dropped)
        assertEquals(3, q.size)
        assertEquals(3, q.peak)                       // never above the limit
        assertEquals("3", q.pollFirst())
    }

    @Test fun overflowDropNewestDropsTheIncomingItem() {
        val q = q()
        listOf("1", "2", "3").forEach { q.offer(it, null, false, 3, OverflowPolicy.DROP_NEWEST) }
        val dropped = q.offer("4", null, false, 3, OverflowPolicy.DROP_NEWEST)
        assertEquals(listOf("4"), dropped)
        assertEquals(1L, q.dropped)
        assertEquals("1", q.pollFirst())
    }

    @Test fun sameKeyReplacesOlderPendingAndCountsReplaced() {
        val q = q()
        q.offer("x1", "K", true, 8, OverflowPolicy.DROP_OLDEST)
        q.offer("y", "OTHER", true, 8, OverflowPolicy.DROP_OLDEST)
        q.offer("x2", "K", true, 8, OverflowPolicy.DROP_OLDEST)
        assertEquals(1L, q.replaced)
        assertEquals(0L, q.dropped)
        assertEquals(2, q.size)
        assertEquals("y", q.pollFirst())
        assertEquals("x2", q.pollFirst())
    }

    @Test fun keyWithoutReplaceFlagDoesNotReplace() {
        val q = q()
        q.offer("t1", "K", false, 8, OverflowPolicy.DROP_OLDEST)   // key set but replace=false: ordered, never replaced
        q.offer("t2", "K", false, 8, OverflowPolicy.DROP_OLDEST)
        assertEquals(0L, q.replaced)
        assertEquals(2, q.size)
    }

    @Test fun clearEmptiesWithoutCountingDrops() {
        val q = q()
        q.offer("a", null, false, 8, OverflowPolicy.DROP_OLDEST)
        q.clear()
        assertEquals(0, q.size)
        assertEquals(0L, q.dropped)
    }

    @Test fun resetStatsZeroesCountersAndRestartsPeakFromSize() {
        val q = q()
        listOf("1", "2", "3", "4").forEach { q.offer(it, null, false, 2, OverflowPolicy.DROP_OLDEST) }
        q.pollFirst()
        assertTrue(q.dropped > 0 && q.executed > 0)
        q.resetStats()
        assertEquals(0L, q.dropped)
        assertEquals(0L, q.executed)
        assertEquals(0L, q.replaced)
        assertEquals(q.size, q.peak)
    }

    @Test fun headTracksTheNextCommandThroughOfferPollAndClear() {
        val q = q()
        assertNull(q.head)
        q.offer("a", null, false, 8, OverflowPolicy.DROP_OLDEST)
        q.offer("b", null, false, 8, OverflowPolicy.DROP_OLDEST)
        assertEquals("a", q.head)
        q.pollFirst()
        assertEquals("b", q.head)
        q.pollFirst()
        assertNull(q.head)
        q.offer("c", null, false, 8, OverflowPolicy.DROP_OLDEST)
        q.clear()
        assertNull(q.head)
    }

    @Test fun headFollowsOverflowAndReplacement() {
        val q = q()
        listOf("1", "2", "3").forEach { q.offer(it, null, false, 2, OverflowPolicy.DROP_OLDEST) }
        assertEquals("2", q.head)                       // "1" was dropped
        val r = q()
        r.offer("x1", "K", true, 8, OverflowPolicy.DROP_OLDEST)
        r.offer("x2", "K", true, 8, OverflowPolicy.DROP_OLDEST)
        assertEquals("x2", r.head)                      // "x1" was replaced
    }

    @Test fun maxBelowOneIsTreatedAsOne() {
        val q = q()
        q.offer("a", null, false, 0, OverflowPolicy.DROP_OLDEST)
        q.offer("b", null, false, -5, OverflowPolicy.DROP_OLDEST)
        assertEquals(1, q.size)
        assertEquals(1L, q.dropped)
    }
}
