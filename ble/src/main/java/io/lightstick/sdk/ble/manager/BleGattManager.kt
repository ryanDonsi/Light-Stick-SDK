package io.lightstick.sdk.ble.manager

import android.Manifest
//import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import io.lightstick.sdk.ble.delegate.GattEventDelegate
import io.lightstick.sdk.ble.manager.internal.CmdQueueConfig
import io.lightstick.sdk.ble.manager.internal.CmdQueueManager
import io.lightstick.sdk.ble.manager.internal.OverflowPolicy
import io.lightstick.sdk.ble.model.GattOperationResult
import io.lightstick.sdk.ble.model.GattOperationResult.Reason.*
//import io.lightstick.sdk.ble.model.WriteRequest
import io.lightstick.sdk.ble.util.UuidConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
 * Manages BLE GATT connections, serialized operations, and callbacks.
 *
 * Features:
 * - **Serialized per-address command queue** with rate-limit, overflow control, and coalescing.
 * - **Connection lifecycle** via [connectionStateFlow].
 * - **Actually-connected set** via [connectedAddresses] (added only after service discovery).
 * - MTU tracking and helpers for read/write/notify/descriptor ops.
 *
 * @constructor
 * @param context Application context used to open GATT connections.
 */
class BleGattManager(private val context: Context) {

    // -------------------------------------------------------------------------
    // System services
    // -------------------------------------------------------------------------

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    // -------------------------------------------------------------------------
    // Command queue (rate-limit / overflow / coalescing)
    // -------------------------------------------------------------------------

    /**
     * Internal command queue manager. Serialized per-address execution,
     * optional min-interval and overflow handling, optional coalescing.
     * Configure via [configureCommandQueue].
     */
    private val queueManager = CmdQueueManager()


    /**
     * Configure the BLE command queue with direct parameters.
     *
     * @param minIntervalMs Minimum interval between **executed** BLE ops
     *                      per device address, in milliseconds. (Rate limit)
     * @param maxQueueSize  Maximum number of pending commands per address
     *                      before the overflow policy applies. (>= 1 recommended)
     *
     * @throws IllegalArgumentException if minIntervalMs < 0 or maxQueueSize < 1
     *
     * Example:
     * ```
     * BleSdk.gattManager.configureCommandQueue(
     *     minIntervalMs = 30,
     *     maxQueueSize = 64
     * )
     * ```
     */
    fun configureCommandQueue(
        minIntervalMs: Long,
        maxQueueSize: Int
    ) {
        queueManager.configureCommandQueue(
            minIntervalMs = minIntervalMs,
            maxQueueSize = maxQueueSize
        )
    }

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    /**
     * Map of device address to BluetoothGatt instances.
     * NOTE: An entry may exist while the device is still "Connecting".
     * Do **not** use this map alone as the truth for "connected".
     */
    private val gattMap = mutableMapOf<String, BluetoothGatt>()

    /** Map of device address to negotiated MTU value. */
    private val mtuMap = mutableMapOf<String, Int>()

    /** Optional observers for low-level GATT callbacks. */
    private val delegates = mutableListOf<GattEventDelegate>()

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
    private val _connectionStateFlow = MutableStateFlow<BleConnectionState?>(null)

    /** Read-only flow of connection state updates. */
    val connectionStateFlow: StateFlow<BleConnectionState?> = _connectionStateFlow

    /**
     * Read-only set of device addresses that are **actually connected**.
     * A device is added only after service discovery succeeds, and removed
     * on any disconnect/failure/cleanup path.
     *
     * @sample
     * viewModelScope.launch {
     *     bleGattManager.connectedAddresses.collect { set ->
     *         Log.d("BLE", "Connected devices: ${set.size} -> $set")
     *     }
     * }
     */
    private val _connectedAddresses = MutableStateFlow<Set<String>>(emptySet())

    /** Flow exposing the actually connected address set. */
    val connectedAddresses: StateFlow<Set<String>> = _connectedAddresses

    /** Adds [address] to the connected set. Called only when connection is fully established. */
    private fun markConnected(address: String) {
        _connectedAddresses.update { it + address }
    }

    /** Removes [address] from the connected set. Called on any disconnect/failure/cleanup path. */
    private fun markDisconnected(address: String) {
        _connectedAddresses.update { it - address }
    }

    /**
     * Returns a one-shot snapshot of the currently connected device addresses.
     * Prefer collecting [connectedAddresses] if you need to react to changes.
     *
     * @return A new [List] containing the MAC addresses currently marked as connected.
     */
    fun getConnectedList(): List<String> = _connectedAddresses.value.toList()

    /** Addresses currently undergoing a connect attempt (to prevent duplicates). */
    private val connectingDevices = ConcurrentHashMap<String, Long>()

    /** Connect timeout handling on the main thread. */
    private val timeoutHandler = Handler(Looper.getMainLooper())

    /** Map of device address to timeout runnable. */
    private val timeoutRunnables = mutableMapOf<String, Runnable>()

    // If you still use the legacy WriteQueueManager-based path, keep this and writeInternal().
    // @SuppressLint("MissingPermission")
    // private val writeQueueManager = WriteQueueManager { request -> writeInternal(request) }

    // -------------------------------------------------------------------------
    // Delegation
    // -------------------------------------------------------------------------

    /**
     * Register an additional delegate for low-level GATT callbacks.
     *
     * @param delegate [GattEventDelegate] to receive callback mirrors.
     */
    fun addDelegate(delegate: GattEventDelegate) {
        delegates.add(delegate)
    }

    /**
     * Get the current [BluetoothGatt] instance for [address], if any.
     *
     * @param address Device MAC address.
     * @return The [BluetoothGatt] instance or `null` if not present.
     */
    fun getGatt(address: String): BluetoothGatt? = gattMap[address]

    /**
     * Set or update the [BluetoothGatt] instance for [address].
     *
     * @param address Device MAC address.
     * @param gatt A connected (or connecting) [BluetoothGatt] instance.
     */
    fun setGatt(address: String, gatt: BluetoothGatt) {
        gattMap[address] = gatt
    }

    /**
     * Update the negotiated MTU value for [address].
     *
     * @param address Device MAC address.
     * @param mtu Negotiated MTU.
     */
    fun updateMtu(address: String, mtu: Int) {
        mtuMap[address] = mtu
    }

    /**
     * Get the maximum payload size given current MTU for [address] (`MTU - 3`).
     *
     * @param address Device MAC address.
     * @return Payload size in bytes, or `null` if MTU is unknown.
     */
    fun getMtuPayloadSize(address: String): Int? = mtuMap[address]?.minus(3)

    /**
     * Check if [address] is **actually connected**.
     * This is true only after service discovery succeeded.
     *
     * @param address Device MAC address.
     * @return `true` if the device is in the actually-connected set, `false` otherwise.
     */
    fun isConnected(address: String): Boolean =
        _connectedAddresses.value.contains(address)

    // -------------------------------------------------------------------------
    // Connect / Disconnect
    // -------------------------------------------------------------------------

    /**
     * Initiate a GATT connection to the device at [address].
     *
     * Behavior:
     * - If already connected, emits [BleConnectionState.Connected] immediately.
     * - If a connect is already in-flight for [address], the call is ignored.
     * - A **10s timeout** is scheduled; on timeout, a [BleConnectionState.Failed] is emitted.
     *
     * @param address Target device MAC address.
     * @param autoConnect If `true`, background auto-connect; otherwise immediate connect.
     *
     * @throws SecurityException If the caller lacks `BLUETOOTH_CONNECT` permission.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(address: String, autoConnect: Boolean = false) {
        if (isConnected(address)) {
            _connectionStateFlow.value = BleConnectionState.Connected(address)
            return
        }
        if (connectingDevices.containsKey(address)) return

        val device = bluetoothAdapter.getRemoteDevice(address)
        val gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)

        // Keep a reference; this alone does not mean "connected".
        setGatt(address, gatt)

        connectingDevices[address] = System.currentTimeMillis()
        _connectionStateFlow.value = BleConnectionState.Connecting(address)

        // 10s connect timeout → treat as failed and cleanup
        timeoutRunnables[address] = Runnable {
            disconnect(address)
            _connectionStateFlow.value = BleConnectionState.Failed(address, BluetoothGatt.GATT_FAILURE)
        }.also { timeoutHandler.postDelayed(it, 10_000L) }
    }

    /**
     * Disconnect and close the GATT for [address]; cancel timeouts and queues,
     * update tracking and emit [BleConnectionState.Disconnected].
     *
     * @param address Target device MAC address.
     *
     * @throws SecurityException If the caller lacks `BLUETOOTH_CONNECT` permission.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect(address: String) {
        cleanupConnection(address, null)
        _connectionStateFlow.value = BleConnectionState.Disconnected(address)
    }

    /**
     * Centralized cleanup for any disconnect/failure path:
     * - Clear queued BLE operations for [address].
     * - Disconnect & close the GATT (provided or from [gattMap]).
     * - Cancel timeout runnable.
     * - Clear "connecting" bookkeeping.
     * - Remove from the actually-connected set.
     *
     * @param address Device MAC address to cleanup.
     * @param gatt Optional GATT instance to disconnect/close; if null, looks up from [gattMap].
     *
     * @throws SecurityException If the caller lacks `BLUETOOTH_CONNECT` permission.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun cleanupConnection(address: String, gatt: BluetoothGatt? = null) {
        // Clear queued BLE operations
        queueManager.clear(address)

        // Disconnect & close the GATT
        (gatt ?: gattMap.remove(address))?.apply {
            try { disconnect() } catch (_: Throwable) {}
            try { close() } catch (_: Throwable) {}
        }

        // Cancel timeouts and reset connecting state
        timeoutRunnables.remove(address)?.let(timeoutHandler::removeCallbacks)
        connectingDevices.remove(address)

        // Drop from "actually connected"
        markDisconnected(address)
    }

    // -------------------------------------------------------------------------
    // Data plane (write / descriptor / read / notify)
    // -------------------------------------------------------------------------

    /**
     * Enqueue a characteristic write to [characteristicUUID] under [serviceUUID].
     *
     * Queue semantics:
     * - Serialized per address; rate-limited by [CmdQueueConfig.minIntervalMs] between **completed** commands.
     * - If [coalesceKey] is not null and [replaceIfSameKey] is `true`, older **pending** commands
     *   with the same key are removed before this command is enqueued (latest-wins).
     * - If pending size exceeds [CmdQueueConfig.maxQueueSizePerAddress], **oldest pending** are dropped.
     *
     * @param address Target device MAC address.
     * @param serviceUUID GATT service UUID containing the characteristic.
     * @param characteristicUUID GATT characteristic UUID to write to.
     * @param data Bytes to write.
     * @param writeType GATT write type (default: [BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT]).
     * @param operation Optional label used for logs/diagnostics.
     * @param coalesceKey Optional key for coalescing similar commands.
     * @param replaceIfSameKey If `true`, coalesce by removing older **pending** commands that share [coalesceKey].
     * @return [GattOperationResult] representing immediate enqueue success/failure.
     * @throws SecurityException If the caller lacks `BLUETOOTH_CONNECT` permission.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendData(
        address: String,
        serviceUUID: UUID,
        characteristicUUID: UUID,
        data: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        operation: String = "sendData",
        coalesceKey: String? = null,
        replaceIfSameKey: Boolean = false
    ): GattOperationResult {
        val gatt = getGatt(address)
            ?: return GattOperationResult.Failure(address, operation, GattUnavailable)

        val service = gatt.getService(serviceUUID)
            ?: return GattOperationResult.Failure(address, operation, ServiceNotFound)

        val char = service.getCharacteristic(characteristicUUID)
            ?: return GattOperationResult.Failure(address, operation, CharacteristicNotFound)

        Log.d(
            "Telink-BLE",
            "🟢 sendData: service=$serviceUUID, char=$characteristicUUID, data=${
                data.joinToString(" ") { "%02X".format(it) }
            }"
        )

        @Suppress("DEPRECATION")
        queueManager.enqueue(
            address = address,
            operation = operation,
            coalesceKey = coalesceKey,
            replaceIfSameKey = replaceIfSameKey
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                char.writeType = writeType
                // Async; queue advances at onCharacteristicWrite()
                gatt.writeCharacteristic(char, data, writeType)
            } else {
                char.writeType = writeType
                char.value = data
                gatt.writeCharacteristic(char)
            }
        }

        return GattOperationResult.Success(address, operation)
    }

    /**
     * Enqueue a descriptor write to [descriptorUUID] under [characteristicUUID]/[serviceUUID].
     * See [sendData] for queue/coalescing/overflow semantics.
     *
     * @param address Target device MAC address.
     * @param serviceUUID GATT service UUID.
     * @param characteristicUUID GATT characteristic UUID containing the descriptor.
     * @param descriptorUUID GATT descriptor UUID to write to.
     * @param value Bytes to write to the descriptor.
     * @param operation Optional label used for logs/diagnostics.
     * @param coalesceKey Optional key for coalescing similar commands.
     * @param replaceIfSameKey If `true`, coalesce by removing older **pending** commands that share [coalesceKey].
     * @return [GattOperationResult] representing immediate enqueue success/failure.
     * @throws SecurityException If the caller lacks `BLUETOOTH_CONNECT` permission.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendDescriptor(
        address: String,
        serviceUUID: UUID,
        characteristicUUID: UUID,
        descriptorUUID: UUID,
        value: ByteArray,
        operation: String = "sendDescriptor",
        coalesceKey: String? = null,
        replaceIfSameKey: Boolean = false
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
        queueManager.enqueue(
            address = address,
            operation = operation,
            coalesceKey = coalesceKey,
            replaceIfSameKey = replaceIfSameKey
        ) {
            descriptor.value = value
            // Async; queue advances at onDescriptorWrite()
            gatt.writeDescriptor(descriptor)
        }

        return GattOperationResult.Success(address, operation)
    }

    /**
     * Enable notifications for [characteristicUUID] by writing CCCD (0x2902).
     *
     * @param address Target device MAC address.
     * @param serviceUUID GATT service UUID.
     * @param characteristicUUID GATT characteristic UUID to enable notification on.
     * @param operation Optional label used for logs/diagnostics.
     * @return [GattOperationResult] representing immediate enqueue success/failure.
     * @throws SecurityException If the caller lacks `BLUETOOTH_CONNECT` permission.
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
        queueManager.enqueue(address = address, operation = operation) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor) // Async; onDescriptorWrite()
        }

        return GattOperationResult.Success(address, operation)
    }

    /**
     * Enqueue a characteristic read operation.
     *
     * @param address Target device MAC address.
     * @param serviceUUID GATT service UUID.
     * @param characteristicUUID GATT characteristic UUID to read.
     * @param operation Optional label used for logs/diagnostics.
     * @return [GattOperationResult] representing immediate enqueue success/failure.
     * @throws SecurityException If the caller lacks `BLUETOOTH_CONNECT` permission.
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

        queueManager.enqueue(address = address, operation = operation) {
            gatt.readCharacteristic(char) // Async; onCharacteristicRead()
        }

        return GattOperationResult.Success(address, operation)
    }

    // -------------------------------------------------------------------------
    // Internal WriteRequest executor (legacy compatibility)
    // -------------------------------------------------------------------------

//    /**
//     * Execute a [WriteRequest] using API level–aware calls.
//     *
//     * @param request A write request (characteristic or descriptor).
//     * @return `true` if the platform accepted the write call (does **not** indicate BLE success).
//     * @throws SecurityException If the caller lacks `BLUETOOTH_CONNECT` permission.
//     */
//    @Suppress("DEPRECATION")
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun writeInternal(request: WriteRequest): Boolean {
//        return when (request) {
//            is WriteRequest.CharacteristicWrite -> {
//                val gatt = request.gatt
//                val c = request.characteristic
//                val data = request.data
//                val writeType = request.writeType
//
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                    gatt.writeCharacteristic(c, data, writeType) == BluetoothStatusCodes.SUCCESS
//                } else {
//                    c.value = data
//                    c.writeType = writeType
//                    gatt.writeCharacteristic(c)
//                }
//            }
//
//            is WriteRequest.DescriptorWrite -> {
//                val gatt = request.gatt
//                val d = request.descriptor
//                val data = request.data
//
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                    gatt.writeDescriptor(d, data) == BluetoothStatusCodes.SUCCESS
//                } else {
//                    d.value = data
//                    gatt.writeDescriptor(d)
//                }
//            }
//        }
//    }

    // -------------------------------------------------------------------------
    // GATT callback
    // -------------------------------------------------------------------------

    /**
     * System GATT callback. Translates platform events into
     * - [connectionStateFlow] updates
     * - [connectedAddresses] updates
     * - Queue advancement via [CmdQueueManager.signalCommandComplete]
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private val gattCallback = object : BluetoothGattCallback() {

        /**
         * @param gatt The GATT instance reporting the change.
         * @param status Status code (e.g., [BluetoothGatt.GATT_SUCCESS]).
         * @param newState New connection state (e.g., [BluetoothProfile.STATE_CONNECTED]).
         *
         * @throws SecurityException If the caller lacks `BLUETOOTH_CONNECT` permission.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            val address = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Stop connect-timeout and proceed to discovery
                    timeoutRunnables.remove(address)?.let(timeoutHandler::removeCallbacks)
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    // Uniform cleanup for both normal disconnect and failures
                    val wasConnecting = connectingDevices.containsKey(address)
                    cleanupConnection(address, gatt)

                    _connectionStateFlow.value =
                        if (wasConnecting && status != BluetoothGatt.GATT_SUCCESS)
                            BleConnectionState.Failed(address, status)
                        else
                            BleConnectionState.Disconnected(address)
                }
            }
        }

        /**
         * @param gatt The GATT instance that finished service discovery.
         * @param status [BluetoothGatt.GATT_SUCCESS] on success; otherwise failure code.
         *
         * @throws SecurityException If the caller lacks `BLUETOOTH_CONNECT` permission.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _connectionStateFlow.value = BleConnectionState.Connected(address)

                // Connection fully established → reflect in the connected set
                connectingDevices.remove(address)
                markConnected(address)

                // Continue with MTU request (optional)
                gatt.requestMtu(517)

                // (Optional) Example logging for battery characteristic
                val service = gatt.getService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"))
                val characteristic =
                    service?.getCharacteristic(UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"))
                Log.d("BLE", "Battery properties: ${characteristic?.properties}")
            } else {
                // Discovery failed → treat as connection failure
                cleanupConnection(address, gatt)
                _connectionStateFlow.value = BleConnectionState.Failed(address, status)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            delegates.forEach { it.onCharacteristicRead(gatt, characteristic, status) }
            queueManager.signalCommandComplete(gatt.device.address)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            delegates.forEach { it.onCharacteristicWrite(gatt, characteristic, status) }
            queueManager.signalCommandComplete(gatt.device.address)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            delegates.forEach { it.onDescriptorWrite(gatt, descriptor, status) }
            queueManager.signalCommandComplete(gatt.device.address)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            delegates.forEach { it.onCharacteristicChanged(gatt, characteristic) }
            // Notifications are not part of the serialized queue.
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            updateMtu(gatt.device.address, mtu)
            delegates.forEach { it.onMtuChanged(gatt, mtu, status) }
        }
    }
}
