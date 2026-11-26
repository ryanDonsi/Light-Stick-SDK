package com.lightstick.internal.ble

import com.lightstick.internal.ble.queue.CmdQueueConfig
import com.lightstick.internal.ble.queue.CmdQueueManager
import com.lightstick.internal.ble.queue.OverflowPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Many threads enqueue and complete at once (like main + IO coroutines + GATT binder callbacks):
 * every command must run exactly once, one at a time, and only on the queue thread.
 */
class CmdQueueManagerThreadTest {

    /** A real single queue thread, like the SDK's BLE thread. */
    private class ExecutorScheduler : CmdQueueManager.Scheduler {
        val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "queue") }
        private val futures = ConcurrentHashMap<Runnable, ScheduledFuture<*>>()
        override fun execute(r: Runnable) {
            if (Thread.currentThread().name == "queue") r.run() else exec.execute(r)
        }
        override fun post(r: Runnable, delayMs: Long) {
            futures[r] = exec.schedule({ futures.remove(r); r.run() }, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }
        override fun cancel(r: Runnable) { futures.remove(r)?.cancel(false) }
        override fun nowMs(): Long = System.nanoTime() / 1_000_000
    }

    @Test fun concurrentEnqueueAndCompletionRunEachCommandOnceOnTheQueueThread() {
        val s = ExecutorScheduler()
        val q = CmdQueueManager(
            CmdQueueConfig(minIntervalMs = 0, maxQueueSizePerAddress = 10_000, overflowPolicy = OverflowPolicy.DROP_OLDEST),
            commandTimeoutMs = 5_000,
            scheduler = s
        )
        val addr = "AA:BB:CC:DD:EE:FF"
        val producers = 8
        val perProducer = 250
        val total = producers * perProducer
        val ran = AtomicInteger()
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        val wrongThread = AtomicInteger()
        val done = CountDownLatch(total)
        val completer = Executors.newFixedThreadPool(4)   // plays the GATT binder threads

        val pool = Executors.newFixedThreadPool(producers)
        repeat(producers) { p ->
            pool.execute {
                repeat(perProducer) { i ->
                    q.enqueue(addr, "p$p-$i") {
                        if (Thread.currentThread().name != "queue") wrongThread.incrementAndGet()
                        val c = concurrent.incrementAndGet()
                        maxConcurrent.accumulateAndGet(c, ::maxOf)
                        ran.incrementAndGet()
                        completer.execute {
                            concurrent.decrementAndGet()
                            q.signalComplete(addr)
                            done.countDown()
                        }
                    }
                }
            }
        }

        assertTrue("timed out, ran=${ran.get()}", done.await(20, TimeUnit.SECONDS))
        assertEquals(total, ran.get())
        assertEquals(1, maxConcurrent.get())
        assertEquals(0, wrongThread.get())
        assertEquals(total.toLong(), q.stats(addr).executed)
        pool.shutdownNow(); completer.shutdownNow(); s.exec.shutdownNow()
    }
}
