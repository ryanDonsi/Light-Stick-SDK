package io.lightstick.sdk.ble.manager

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import io.lightstick.sdk.ble.delegate.GattEventDelegate
import io.lightstick.sdk.ble.manager.internal.CmdQueueManager
import io.lightstick.sdk.ble.model.GattOperationResult
import io.lightstick.sdk.ble.model.GattOperationResult.Reason.*
import io.lightstick.sdk.ble.model.WriteRequest
import io.lightstick.sdk.ble.util.UuidConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents the BLE connection state of a device.
 * Used with [BleGattManager.connectionStateFlow] to notify connection progress and errors.
 */
sealed class BleConnectionState {

    /**
     * The device is successfully connected via GATT.
     *
     * @param address MAC address of the connected BLE device.
     */
    data class Connected(val address: String) : BleConnectionState()

    /**
     * The connection attempt to the device is currently in progress.
     *
     * @param address MAC address of the target BLE device.
     */
    data class Connecting(val address: String) : BleConnectionState()

    /**
     * The device has been disconnected or manually disconnected.
     *
     * @param address MAC address of the BLE device that is now disconnected.
     */
    data class Disconnected(val address: String) : BleConnectionState()

    /**
     * The connection attempt failed due to an error.
     *
     * @param address MAC address of the BLE device.
     * @param errorCode BluetoothGatt error code indicating the reason for failure.
     */
    data class Failed(val address: String, val errorCode: Int) : BleConnectionState()
}


/**
 * Manages BLE GATT connections, read/write operations, and delegates events.
 *
 * Supports write queue, MTU tracking, and exposes connection state via Flow.
 *
 * @property context Application context
 */
class BleGattManager(private val context: Context) {

    // Bluetooth system services
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    private val queueManager = CmdQueueManager()

    /**
     * Map of connected device address to BluetoothGatt instances.
     * Used to perform GATT operations on connected devices.
     */
    private val gattMap = mutableMapOf<String, BluetoothGatt>()

    /**
     * Map of device address to negotiated MTU value.
     * Used to calculate payload size for data transmission.
     */
    private val mtuMap = mutableMapOf<String, Int>()

    /**
     * List of GATT event delegates (observers).
     * Notified on GATT callback events (read, write, notification, etc.).
     */
    private val delegates = mutableListOf<GattEventDelegate>()

    /**
     * Internal mutable connection state holder.
     * Emits changes in the BLE connection state including:
     * - [BleConnectionState.Connecting]: When attempting to connect to a device
     * - [BleConnectionState.Connected]: When a connection is successfully established
     * - [BleConnectionState.Disconnected]: When the connection is lost or intentionally disconnected
     * - [BleConnectionState.Failed]: When the connection attempt fails (e.g., timeout or GATT error)
     *
     * Used internally and exposed as [connectionStateFlow].
     */
    private val _connectionStateFlow = MutableStateFlow<BleConnectionState?>(null)

    /**
     * Connection state flow that emits the current BLE connection status.
     *
     * This flow can be collected to react to connection lifecycle events such as:
     * - Connecting
     * - Connected
     * - Disconnected
     * - Failed (with error code)
     *
     * The emitted value is a sealed class [BleConnectionState] which contains:
     * - `address`: MAC address of the target device
     * - `errorCode`: (only in [BleConnectionState.Failed]) the GATT failure code
     *
     * @see BleConnectionState
     *
     * @sample
     * viewModelScope.launch {
     *     bleGattManager.connectionStateFlow.collect { state ->
     *         when (state) {
     *             is BleConnectionState.Connecting ->
     *                 Log.d("BLE", "Connecting to ${state.address}")
     *             is BleConnectionState.Connected ->
     *                 Log.d("BLE", "Connected to ${state.address}")
     *             is BleConnectionState.Disconnected ->
     *                 Log.w("BLE", "Disconnected from ${state.address}")
     *             is BleConnectionState.Failed ->
     *                 Log.e("BLE", "Connection failed to ${state.address}, error=${state.errorCode}")
     *             null -> { /* Initial state */ }
     *         }
     *     }
     * }
     */
    val connectionStateFlow: StateFlow<BleConnectionState?> = _connectionStateFlow


    /**
     * Write queue manager for serialized GATT write operations.
     * Prevents write collisions and handles retries internally.
     */
    @SuppressLint("MissingPermission")
    private val writeQueueManager = WriteQueueManager { request -> writeInternal(request) }

    /**
     * Tracks addresses of devices currently undergoing connection attempts.
     * Helps avoid duplicate connection calls.
     */
    private val connectingDevices = ConcurrentHashMap<String, Long>()

    /**
     * Handler for managing connection timeout callbacks on the main thread.
     * Ensures connection attempt does not hang indefinitely.
     */
    private val timeoutHandler = Handler(Looper.getMainLooper())

    /**
     * Map of device address to timeout Runnable for cancelling timed-out connections.
     */
    private val timeoutRunnables = mutableMapOf<String, Runnable>()

    /**
     * Adds a delegate to receive GATT callback events.
     *
     * @param delegate Instance implementing [GattEventDelegate]
     */
    fun addDelegate(delegate: GattEventDelegate) {
        delegates.add(delegate)
    }

    /**
     * Returns the BluetoothGatt instance associated with a device address.
     *
     * @param address MAC address of the BLE device
     * @return BluetoothGatt instance or null if not connected
     */
    fun getGatt(address: String): BluetoothGatt? = gattMap[address]

    /**
     * Registers or updates the BluetoothGatt instance for a given device address.
     *
     * This method is used when manually assigning a GATT object after a connection.
     *
     * @param address MAC address of the BLE device
     * @param gatt BluetoothGatt instance associated with the address
     */
    fun setGatt(address: String, gatt: BluetoothGatt) {
        gattMap[address] = gatt
    }

    /**
     * Updates the cached MTU value for the given device.
     *
     * @param address MAC address of the BLE device
     * @param mtu Negotiated MTU size
     */
    fun updateMtu(address: String, mtu: Int) {
        mtuMap[address] = mtu
    }


    /**
     * Returns maximum payload size usable with the current MTU (MTU - 3).
     *
     * @param address MAC address of the BLE device
     * @return Number of payload bytes or null if MTU not negotiated
     */
    fun getMtuPayloadSize(address: String): Int? = mtuMap[address]?.minus(3)

    /**
     * Returns true if the device is currently connected via GATT.
     *
     * @param address MAC address of the BLE device
     * @return Boolean indicating connection status
     */
    fun isConnected(address: String): Boolean = gattMap.containsKey(address)

    /**
     * Initiates a GATT connection to the specified BLE device.
     *
     * If a connection is already established (tracked via gattMap), the connection state is set to Connected immediately.
     * If a connection attempt is already in progress for the given address, the function returns early.
     *
     * This method uses `BluetoothDevice.connectGatt()` with the specified `autoConnect` parameter and enforces
     * a 10-second timeout for the connection attempt. If the connection is not established within that time,
     * it will be considered failed.
     *
     * @param address The MAC address of the target BLE device.
     * @param autoConnect If true, the system will automatically connect when the device becomes available (background reconnection).
     *                    If false, the connection attempt is immediate (foreground connection).
     *
     * @throws SecurityException If the app lacks BLUETOOTH_CONNECT permission.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(address: String, autoConnect: Boolean = false) {
        if (gattMap.containsKey(address)) {
            _connectionStateFlow.value = BleConnectionState.Connected(address)
            return
        }

        if (connectingDevices.containsKey(address)) return

        val device = bluetoothAdapter.getRemoteDevice(address)
        val gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        setGatt(address, gatt)
        connectingDevices[address] = System.currentTimeMillis()
        _connectionStateFlow.value = BleConnectionState.Connecting(address)

        timeoutRunnables[address] = Runnable {
            disconnect(address)
            _connectionStateFlow.value = BleConnectionState.Failed(address, BluetoothGatt.GATT_FAILURE)
        }.also {
            timeoutHandler.postDelayed(it, 10_000L)
        }
    }

    /**
     * Disconnects from the BLE device and closes the GATT instance.
     *
     * @param address MAC address of the BLE device
     * @throws SecurityException if BLUETOOTH_CONNECT permission is missing
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect(address: String) {
        gattMap.remove(address)?.apply {
            disconnect()
            close()
        }
        timeoutRunnables.remove(address)?.let(timeoutHandler::removeCallbacks)
        connectingDevices.remove(address)
        _connectionStateFlow.value = BleConnectionState.Disconnected(address)
    }

    /**
     * Sends data to the specified GATT characteristic using a write queue.
     *
     * @param address MAC address of the BLE device
     * @param serviceUUID UUID of the GATT service
     * @param characteristicUUID UUID of the GATT characteristic
     * @param data Byte array to write
     * @param writeType Write type (default = WRITE_TYPE_DEFAULT)
     * @param operation Operation label used in logs and error tracing
     * @return [GattOperationResult] indicating success or failure
     * @throws SecurityException if BLUETOOTH_CONNECT permission is missing
     *
     * @sample
     * ```
     * val result = bleGattManager.sendData(
     *     "00:11:22:33:44:55",
     *     UUID_LED_SERVICE,
     *     UUID_COLOR_CHAR,
     *     byteArrayOf(0xFF.toByte(), 0x00, 0x00)
     * )
     * ```
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendData(
        address: String,
        serviceUUID: UUID,
        characteristicUUID: UUID,
        data: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        operation: String = "sendData"
    ): GattOperationResult {
        val gatt = getGatt(address)
            ?: return GattOperationResult.Failure(address, operation, GattUnavailable)

        val service = gatt.getService(serviceUUID)
            ?: return GattOperationResult.Failure(address, operation, ServiceNotFound)

        val char = service.getCharacteristic(characteristicUUID)
            ?: return GattOperationResult.Failure(address, operation, CharacteristicNotFound)

        Log.d("Telink-BLE", "🟢 sendData: service=$serviceUUID, char=$characteristicUUID, data=${data.joinToString(" ") { "%02X".format(it) }}")

        @Suppress("DEPRECATION")
        queueManager.enqueue(address, operation) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, data, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                char.writeType = writeType
                char.value = data
                gatt.writeCharacteristic(char)
            }
        }

        return GattOperationResult.Success(address, operation)
    }


    /**
     * Sends a write to a descriptor identified by service, characteristic, and descriptor UUIDs.
     *
     * This function validates the BLE connection, locates the descriptor, and
     * enqueues a write request using the command queue. The result only reflects
     * whether the command was accepted and enqueued; the actual BLE write result
     * is handled via the callback.
     *
     * @param address MAC address of the BLE device
     * @param serviceUUID UUID of the GATT service containing the characteristic
     * @param characteristicUUID UUID of the GATT characteristic containing the descriptor
     * @param descriptorUUID UUID of the descriptor to write to
     * @param value Value to write to the descriptor
     * @param operation Optional label for logging and error tracking (default: "sendDescriptor")
     * @return [GattOperationResult] indicating whether the operation was enqueued successfully or failed immediately
     * @throws SecurityException if BLUETOOTH_CONNECT permission is missing
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendDescriptor(
        address: String,
        serviceUUID: UUID,
        characteristicUUID: UUID,
        descriptorUUID: UUID,
        value: ByteArray,
        operation: String = "sendDescriptor"
    ): GattOperationResult {
        val gatt = getGatt(address)
            ?: return GattOperationResult.Failure(address, operation, GattUnavailable)

        val service = gatt.getService(serviceUUID)
            ?: return GattOperationResult.Failure(address, operation, ServiceNotFound)

        val characteristic = service.getCharacteristic(characteristicUUID)
            ?: return GattOperationResult.Failure(address, operation, CharacteristicNotFound)

        val descriptor = characteristic.getDescriptor(descriptorUUID)
            ?: return GattOperationResult.Failure(address, operation, DescriptorNotFound)

        Log.d(
            "Telink-BLE",
            "🟢 sendDescriptor: service=$serviceUUID, char=$characteristicUUID, desc=$descriptorUUID, value=${
                value.joinToString(" ") { "%02X".format(it) }
            }"
        )

        @Suppress("DEPRECATION")
        queueManager.enqueue(address, operation) {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }

        return GattOperationResult.Success(address, operation)
    }

    /**
     * Enables GATT notification for the given characteristic by writing to its CCCD descriptor.
     *
     * This function performs the following steps:
     * 1. Validates GATT connection and locates the target service & characteristic
     * 2. Calls setCharacteristicNotification(...) to register for notifications
     * 3. Writes ENABLE_NOTIFICATION_VALUE to the CCCD descriptor (0x2902) using the BLE command queue
     *
     * The result only reflects whether the command was accepted and queued.
     * Actual result is handled asynchronously via onDescriptorWrite callback.
     *
     * @param address MAC address of the BLE device
     * @param serviceUUID UUID of the GATT service containing the characteristic
     * @param characteristicUUID UUID of the characteristic to enable notification on
     * @param operation Optional operation label for logging and debugging
     * @return [GattOperationResult] indicating whether the notification request was enqueued
     * @throws SecurityException if BLUETOOTH_CONNECT permission is not granted
     *
     * @sample
     * ```kotlin
     * val result = gattManager.enableNotification(
     *     "00:11:22:33:44:55",
     *     UuidConstants.BATTERY_SERVICE,
     *     UuidConstants.BATTERY_LEVEL_CHAR,
     *     operation = "enableBatteryNotification"
     * )
     * ```
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun enableNotification(
        address: String,
        serviceUUID: UUID,
        characteristicUUID: UUID,
        operation: String = "enableNotification"
    ): GattOperationResult {
        val gatt = getGatt(address)
            ?: return GattOperationResult.Failure(address, operation, GattUnavailable)

        val service = gatt.getService(serviceUUID)
            ?: return GattOperationResult.Failure(address, operation, ServiceNotFound)

        val characteristic = service.getCharacteristic(characteristicUUID)
            ?: return GattOperationResult.Failure(address, operation, CharacteristicNotFound)

        val success = gatt.setCharacteristicNotification(characteristic, true)
        if (!success) {
            return GattOperationResult.Failure(address, operation, EnableNotificationFailed)
        }

        val descriptor = characteristic.getDescriptor(UuidConstants.CLIENT_CONFIG_CHAR)
            ?: return GattOperationResult.Failure(address, operation, DescriptorNotFound)

        Log.d(
            "Telink-BLE",
            "🟢 enableNotification: service=$serviceUUID, char=$characteristicUUID, operation=$operation"
        )

        @Suppress("DEPRECATION")
        queueManager.enqueue(address, operation) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        return GattOperationResult.Success(address, operation)
    }


    /**
     * Reads data from a characteristic using the GATT read operation.
     *
     * This function performs characteristic lookup and enqueues the read
     * command using the BLE queue manager to ensure sequential access.
     * The result only reflects whether the read was accepted by the system;
     * the actual data will be returned through BLE callbacks.
     *
     * @param address MAC address of the BLE device
     * @param serviceUUID UUID of the target GATT service
     * @param characteristicUUID UUID of the characteristic to read
     * @param operation Optional operation label for logging (default: "readData")
     * @return GattOperationResult indicating immediate success or failure
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readData(
        address: String,
        serviceUUID: UUID,
        characteristicUUID: UUID,
        operation: String = "readData"
    ): GattOperationResult {
        val gatt = getGatt(address)
            ?: return GattOperationResult.Failure(address, operation, GattUnavailable)

        val service = gatt.getService(serviceUUID)
            ?: return GattOperationResult.Failure(address, operation, ServiceNotFound)

        val char = service.getCharacteristic(characteristicUUID)
            ?: return GattOperationResult.Failure(address, operation, CharacteristicNotFound)

        Log.d("Telink-BLE", "📥 readData: service=$serviceUUID, char=$characteristicUUID")

        queueManager.enqueue(address, operation) {
            gatt.readCharacteristic(char)
        }

        return GattOperationResult.Success(address, operation)
    }


    /**
     * Internal GATT write executor called by [WriteQueueManager].
     *
     * Handles write operations for characteristics and descriptors,
     * supporting Android SDK differences (TIRAMISU and below).
     *
     * @param request WriteRequest (characteristic or descriptor)
     * @return true if write initiated successfully
     */
    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun writeInternal(request: WriteRequest): Boolean {
        return when (request) {
            is WriteRequest.CharacteristicWrite -> {
                val gatt = request.gatt
                val characteristic = request.characteristic
                val data = request.data
                val writeType = request.writeType

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(characteristic, data, writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    characteristic.value = data
                    characteristic.writeType = writeType
                    gatt.writeCharacteristic(characteristic)
                }
            }

            is WriteRequest.DescriptorWrite -> {
                val gatt = request.gatt
                val descriptor = request.descriptor
                val data = request.data

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, data) == BluetoothStatusCodes.SUCCESS
                } else {
                    descriptor.value = data
                    gatt.writeDescriptor(descriptor)
                }
            }
        }
    }

    /**
     * Connection state callback handling GATT lifecycle.
     *
     * On success, triggers MTU request and service discovery.
     * On failure or disconnect, updates [connectionStateFlow] and closes GATT.
     */
    private val gattCallback = object : BluetoothGattCallback() {
        /**
         * Connection state callback handling GATT lifecycle.
         *
         * On success, triggers MTU request and service discovery.
         * On failure or disconnect, updates [connectionStateFlow] and closes GATT.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    timeoutRunnables.remove(address)?.let(timeoutHandler::removeCallbacks)
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val address = gatt.device.address
                    queueManager.clear(address)
                    gattMap.remove(address)?.close()
                    connectingDevices.remove(address)
                    _connectionStateFlow.value = BleConnectionState.Disconnected(address)
                }
            }
        }

        /**
         * Callback for successful service discovery.
         *
         * Triggers MTU update and notifies delegates.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _connectionStateFlow.value = BleConnectionState.Connected(address)
                gatt.requestMtu(517)

                val service = gatt.getService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"))
                val characteristic = service?.getCharacteristic(UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"))

                Log.d("BLE", "Battery properties: ${characteristic?.properties}")

            } else {
                disconnect(address)
                _connectionStateFlow.value = BleConnectionState.Failed(address, status)
            }
        }

        /**
         * Callback for characteristic read result.
         *
         * Forwards to all registered [GattEventDelegate]s.
         */
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            delegates.forEach { it.onCharacteristicRead(gatt, characteristic, status) }
            queueManager.signalCommandComplete(gatt.device.address)
        }

        /**
         * Callback for characteristic write result.
         *
         * Forwards to all registered [GattEventDelegate]s and manages queue.
         */
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            delegates.forEach { it.onCharacteristicWrite(gatt, characteristic, status) }
            queueManager.signalCommandComplete(gatt.device.address)
        }

        /**
         * Callback for descriptor write result.
         *
         * Forwards to delegates and continues write queue.
         */
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            delegates.forEach { it.onDescriptorWrite(gatt, descriptor, status) }
            queueManager.signalCommandComplete(gatt.device.address)
        }

        /**
         * Callback for characteristic notification.
         *
         * Forwards change to all [GattEventDelegate]s.
         */
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            delegates.forEach { it.onCharacteristicChanged(gatt, characteristic) }
        }

        /**
         * Callback for MTU size change result.
         *
         * Updates internal MTU state and informs delegates.
         */
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            updateMtu(gatt.device.address, mtu)
            delegates.forEach { it.onMtuChanged(gatt, mtu, status) }
        }
    }
}
