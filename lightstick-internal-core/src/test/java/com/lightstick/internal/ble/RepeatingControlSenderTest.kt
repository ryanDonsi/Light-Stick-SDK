package com.lightstick.internal.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class RepeatingControlSenderTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sent = CopyOnWriteArrayList<Pair<Int, Long>>()

    @After fun tearDown() = scope.cancel()

    private fun sender(result: Boolean = true, repeat: Int = 3, intervalMs: Long = 30) =
        RepeatingControlSender(scope, { c, m -> sent.add(c to m); result }, repeat, intervalMs)

    @Test fun firstSendIsImmediateThenRepeatsToTotalOfThree() {
        val s = sender()
        assertTrue(s.sendRepeated(4, 0L))
        assertEquals(1, sent.size)                 // first one is synchronous
        Thread.sleep(250)
        assertEquals(3, sent.size)                 // first + 2 repeats, no more
        assertTrue(sent.all { it == (4 to 0L) })
    }

    @Test fun cancelPendingStopsRemainingRepeats() {
        val s = sender(intervalMs = 80)
        s.sendRepeated(4, 0L)
        s.cancelPending()
        Thread.sleep(300)
        assertEquals(1, sent.size)
    }

    @Test fun newSequenceReplacesPreviousOne() {
        val s = sender(intervalMs = 60)
        s.sendRepeated(4, 0L)
        s.sendRepeated(3, 0L)                      // replaces the pending RESUME repeats
        Thread.sleep(300)
        assertEquals(1, sent.count { it.first == 4 })
        assertEquals(3, sent.count { it.first == 3 })
    }

    @Test fun failedFirstSendDoesNotStartRepeats() {
        val s = sender(result = false)
        assertFalse(s.sendRepeated(4, 0L))
        Thread.sleep(200)
        assertEquals(1, sent.size)
    }

    @Test fun repeatOneMeansNoRepeats() {
        val s = sender(repeat = 1)
        s.sendRepeated(4, 0L)
        Thread.sleep(150)
        assertEquals(1, sent.size)
    }

    @Test fun invalidArgumentsRejected() {
        assertThrows(IllegalArgumentException::class.java) { sender(repeat = 0) }
        assertThrows(IllegalArgumentException::class.java) { sender(intervalMs = -1) }
    }
}
