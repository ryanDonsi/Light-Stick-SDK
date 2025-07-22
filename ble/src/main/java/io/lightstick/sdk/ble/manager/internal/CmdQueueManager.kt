package io.lightstick.sdk.ble.manager.internal

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * BLE GATT command queue manager for sequential operation execution.
 *
 * This class ensures that Bluetooth GATT operations (such as read, write, notify)
 * are executed in strict order for each connected device.
 *
 * BLE stacks often fail when multiple GATT operations are issued concurrently,
 * so this queue manager enforces one-at-a-time execution per device address.
 *
 * Each device has its own queue, allowing multiple devices to be managed in parallel.
 */
class CmdQueueManager {

    /** Maps MAC addresses to their operation queues. */
    private val commandQueues: MutableMap<String, ConcurrentLinkedQueue<() -> Boolean>> = ConcurrentHashMap()

    /** Tracks which device addresses are currently executing a command. */
    private val executing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Enqueues a GATT command for the specified BLE device.
     *
     * If no command is currently executing for the device, the command will be executed immediately.
     * Otherwise, it will be added to the queue and run when previous commands complete.
     *
     * @param address MAC address of the BLE device
     * @param operation A human-readable label (used for logging/debugging)
     * @param execute A function representing the GATT command to execute
     *                (e.g., `gatt.writeCharacteristic(...)`). Returns true if successfully invoked.
     *
     * @sample
     * ```kotlin
     * queueManager.enqueue("00:11:22:33:44:55", "writeLed") {
     *     gatt.writeCharacteristic(characteristic)
     * }
     * ```
     */
    fun enqueue(
        address: String,
        operation: String,
        execute: () -> Boolean
    ) {
        val queue = commandQueues.getOrPut(address) { ConcurrentLinkedQueue() }
        queue.add(execute)

        if (executing.add(address)) {
            Log.d("CmdQueue", "🚀 [$address] Start executing: $operation")
            runNext(address)
        } else {
            Log.d("CmdQueue", "⏳ [$address] Queued: $operation")
        }
    }

    /**
     * Executes the next command in the queue for the given device address.
     *
     * This is called either immediately upon enqueue (if no command is running),
     * or after [signalCommandComplete] is invoked to mark the previous command as finished.
     *
     * @param address MAC address of the BLE device
     */
    private fun runNext(address: String) {
        val queue = commandQueues[address] ?: return
        val next = queue.peek() ?: return

        val accepted = try {
            next.invoke()
        } catch (e: Exception) {
            Log.e("CmdQueue", "❌ [$address] Command execution error: ${e.message}", e)
            false
        }

        if (!accepted) {
            Log.w("CmdQueue", "⚠️ [$address] Command rejected by Bluetooth stack or failed to start.")
            queue.poll() // Remove failed command and try the next one
            runNext(address)
        }
        // If successful, completion must be triggered via signalCommandComplete()
    }

    /**
     * Signals that the current GATT operation for the given device has completed.
     *
     * This should be called from GATT callbacks such as:
     * - [android.bluetooth.BluetoothGattCallback.onCharacteristicWrite]
     * - [android.bluetooth.BluetoothGattCallback.onCharacteristicRead]
     * - [android.bluetooth.BluetoothGattCallback.onDescriptorWrite]
     *
     * @param address MAC address of the BLE device whose operation completed
     *
     * @sample
     * ```kotlin
     * override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
     *     queueManager.signalCommandComplete(gatt.device.address)
     * }
     * ```
     */
    fun signalCommandComplete(address: String) {
        val queue = commandQueues[address] ?: return
        queue.poll() // Remove the completed command

        if (queue.isEmpty()) {
            executing.remove(address)
            Log.d("CmdQueue", "✅ [$address] All queued commands completed.")
        } else {
            runNext(address)
        }
    }

    /**
     * Clears the command queue for a specific device address.
     *
     * Use this when a device is disconnected or its operations are canceled.
     *
     * @param address MAC address of the BLE device
     */
    fun clear(address: String) {
        commandQueues[address]?.clear()
        commandQueues.remove(address)
        executing.remove(address)
        Log.d("CmdQueue", "🧹 [$address] Queue cleared.")
    }

    /**
     * Clears all command queues for all devices.
     *
     * This should be used during a global shutdown or BLE subsystem reset.
     */
    fun clearAll() {
        commandQueues.clear()
        executing.clear()
        Log.d("CmdQueue", "🧨 All queues cleared.")
    }
}
