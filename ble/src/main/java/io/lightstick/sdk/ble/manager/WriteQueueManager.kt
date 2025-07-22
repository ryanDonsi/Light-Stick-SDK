package io.lightstick.sdk.ble.manager

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.lightstick.sdk.ble.model.WriteRequest
import java.util.LinkedList
import java.util.Queue

/**
 * Manages BLE write operations in a serialized queue with timeout support.
 *
 * BLE GATT operations (especially characteristic and descriptor writes) must not overlap,
 * and Android does not queue these automatically. This class ensures safe, sequential execution.
 *
 * - Each write is processed one at a time.
 * - Optional timeout triggers fallback if no response is received in time.
 * - Existing pending writes can be cleared before inserting the latest write using [enqueueLatest].
 *
 * @param writeExecutor Function responsible for executing the actual BLE write.
 * Must return `true` if the write was initiated successfully.
 *
 * @sample
 * ```kotlin
 * val queueManager = WriteQueueManager { request -> writeInternal(request) }
 * queueManager.enqueueLatest(
 *     WriteRequest.createForCharacteristic(gatt, char, data)
 * )
 * ```
 */
class WriteQueueManager(
    private val writeExecutor: (WriteRequest) -> Boolean
) {
    /** Internal FIFO queue for pending write requests */
    private val queue: Queue<WriteRequest> = LinkedList()

    /** Indicates whether a write is currently in progress */
    private var isWriting = false

    /** Handler tied to main looper for posting timeouts */
    private val handler = Handler(Looper.getMainLooper())

    /** Runnable that triggers a timeout fallback */
    private var timeoutRunnable: Runnable? = null

    /** Max allowed duration per write before timeout triggers (in milliseconds) */
    private val timeoutMillis = 500L

    /**
     * Clears any pending writes and queues the latest request.
     *
     * This method is commonly used for write types like LED color control
     * where only the most recent command is needed.
     *
     * @param request BLE characteristic or descriptor write request
     */
    fun enqueueLatest(request: WriteRequest) {
        synchronized(queue) {
            if (isWriting) queue.clear() // Optional: drop older queued writes
            queue.offer(request)
            processNext()
        }
    }

    /**
     * Should be called from the BluetoothGattCallback after each write completes.
     *
     * This allows the next request to proceed and cancels any pending timeout.
     */
    fun onWriteComplete() {
        synchronized(queue) {
            isWriting = false
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            timeoutRunnable = null
            processNext()
        }
    }

    /**
     * Processes the next request in the queue if none is currently running.
     *
     * Invokes the [writeExecutor] function for the dequeued [WriteRequest].
     * If the write fails to start, this function will call [onWriteComplete] immediately.
     * If it starts successfully, a timeout will be set.
     */
    private fun processNext() {
        if (isWriting) return
        val next = queue.poll() ?: return
        isWriting = true

        val success = writeExecutor(next)
        if (!success) {
            Log.e("WriteQueueManager", "Write failed for ${next.javaClass.simpleName}")
            onWriteComplete()
            return
        }

        timeoutRunnable = Runnable {
            Log.e("WriteQueueManager", "Write timeout occurred.")
            onWriteComplete()
        }
        handler.postDelayed(timeoutRunnable!!, timeoutMillis)
    }
}
