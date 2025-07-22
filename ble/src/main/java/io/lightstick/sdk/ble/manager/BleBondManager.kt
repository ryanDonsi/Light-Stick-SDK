package io.lightstick.sdk.ble.manager

import android.Manifest
import android.bluetooth.*
import android.content.*
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BLE Bond Manager that manages system-level bonded BLE devices.
 * Emits bond state changes via Flow and triggers auto-GATT connection on success.
 */
class BleBondManager(
    private val context: Context,
    private val gattManager: BleGattManager
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter = bluetoothManager.adapter

    private val receiverRegistered = AtomicBoolean(false)

    private val _bondStateFlow = MutableSharedFlow<BondState>()

    /**
     * Emits bonding state change events for BLE devices using a [SharedFlow].
     *
     * This flow provides a stream of [BondState] events that represent the system-level
     * bonding status of Bluetooth Low Energy (BLE) devices. It can be used by UI, ViewModel,
     * or service layers to react to changes such as successful bonding or unpairing.
     *
     * Emission Source:
     * - Events are emitted internally by the [BroadcastReceiver] registered via [registerReceiver],
     *   which listens for [BluetoothDevice.ACTION_BOND_STATE_CHANGED] broadcasts.
     *
     * Subscribing:
     * - Consumers (e.g., ViewModels) can collect from [bondStateFlow] to handle:
     *   - UI updates when a device is bonded/unbonded
     *   - Automatically triggering connection attempts
     *   - Logging or retry strategies on bonding failures
     *
     * This flow is a [SharedFlow] with no replay cache, meaning subscribers must be actively
     * collecting to receive updates. Bond state changes that occur before collection begins
     * will not be replayed.
     *
     * @sample
     * ```
     * viewModelScope.launch {
     *     bleBondManager.bondStateFlow.collect { state ->
     *         when (state) {
     *             is BondState.Bonded -> { /* connect or show success UI */ }
     *             is BondState.Unbonded -> { /* cleanup or notify */ }
     *         }
     *     }
     * }
     * ```
     *
     * @see BondState
     * @see BluetoothDevice.ACTION_BOND_STATE_CHANGED
     * @see registerReceiver
     */
    val bondStateFlow: SharedFlow<BondState> = _bondStateFlow

    /**
     * Represents the state changes related to Bluetooth Low Energy (BLE) device bonding.
     *
     * This sealed class is used to emit updates via [bondStateFlow] when the bonding status
     * of a device changes. It allows subscribers to respond to pairing success, removal,
     * or failure events in a structured way.
     *
     * States:
     * - [Bonded]: Indicates that the device has been successfully bonded (paired).
     * - [Unbonded]: Indicates that the device is no longer bonded (either unpaired or failed).
     * - [BondingFailed]: Explicit indication of a failed bonding attempt (optional use).
     *
     * Emitted by the internal bond receiver within [BleBondManager].
     *
     * @see BleBondManager.bondStateFlow
     * @see BluetoothDevice.ACTION_BOND_STATE_CHANGED
     */
    sealed class BondState {
        /** Emitted when the device has been successfully bonded (paired). */
        data class Bonded(val device: BluetoothDevice) : BondState()

        /** Emitted when the device is no longer bonded (unpaired or removed). */
        data class Unbonded(val device: BluetoothDevice) : BondState()

        /** Optional state representing an explicitly failed bonding attempt. */
        data class BondingFailed(val device: BluetoothDevice) : BondState()
    }


    /**
     * Registers the internal [BroadcastReceiver] to listen for system BLE bond state changes.
     *
     * This receiver enables the app to respond to bonding events such as:
     * - Bond completed ([BluetoothDevice.BOND_BONDED])
     * - Bonding failed or removed ([BluetoothDevice.BOND_NONE])
     *
     * It is typically used to trigger automatic GATT connection upon successful bonding.
     * Call this once during app initialization (e.g., in [Application.onCreate] or via a DI module).
     * This method is idempotent and safe to call multiple times; registration will only occur once.
     *
     * You must also call [unregisterReceiver] when no longer needed to avoid memory leaks.
     *
     * @see BluetoothDevice.ACTION_BOND_STATE_CHANGED
     * @see unregisterReceiver
     */
    fun registerReceiver() {
        if (receiverRegistered.getAndSet(true)) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        context.registerReceiver(bondReceiver, filter)
    }

    /**
     * Unregisters the internal [BroadcastReceiver] that listens for BLE bond state changes.
     *
     * This should be called when the bond event listener is no longer needed to avoid
     * memory leaks and unintended behavior. For example, call this in an Activity or Application's
     * `onDestroy()` or `onTerminate()` lifecycle method.
     *
     * This method is safe to call multiple times; it will only perform unregistration if the receiver
     * was previously registered via [registerReceiver].
     *
     * @see registerReceiver
     * @see android.content.Context.unregisterReceiver
     */
    fun unregisterReceiver() {
        if (receiverRegistered.getAndSet(false)) {
            context.unregisterReceiver(bondReceiver)
        }
    }

    /**
     * Initiates system-level bonding (pairing) with the specified BLE device.
     *
     * This function attempts to pair with a Bluetooth Low Energy (LE) device using its MAC address.
     * If the bonding process is successfully started, it returns `true`.
     * The actual result of bonding (success/failure) will be delivered asynchronously via
     * a system broadcast ([BluetoothDevice.ACTION_BOND_STATE_CHANGED]), which should be handled
     * by a registered [BroadcastReceiver].
     *
     * Requires [Manifest.permission.BLUETOOTH_CONNECT] on Android 12+.
     *
     * @param address The MAC address of the BLE device to bond with.
     * @return `true` if the bonding process was successfully initiated, `false` if it failed or an error occurred.
     *
     * @see BluetoothDevice.createBond
     * @see BluetoothDevice.ACTION_BOND_STATE_CHANGED
     * @see registerReceiver
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun bindDevice(address: String): Boolean {
        return try {
            val device = adapter.getRemoteDevice(address)
            device.createBond()
        } catch (e: SecurityException) {
            Log.e("BleBondManager", "❌ Bonding failed for $address", e)
            false
        }
    }

    /**
     * Checks whether the given BLE device is already bonded (paired) in the system.
     *
     * This function verifies that the device with the specified MAC address:
     * - Exists in the system's list of bonded devices ([BluetoothAdapter.getBondedDevices])
     * - Is a Bluetooth Low Energy (LE) device ([BluetoothDevice.DEVICE_TYPE_LE])
     *
     * Requires [Manifest.permission.BLUETOOTH_CONNECT] on Android 12+.
     *
     * @param address The MAC address of the target BLE device.
     * @return `true` if the device is bonded and is of LE type, `false` otherwise.
     *
     * @see BluetoothAdapter.getBondedDevices
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isBonded(address: String): Boolean {
        return adapter.bondedDevices.any {
            it.address == address && it.type == BluetoothDevice.DEVICE_TYPE_LE
        }
    }

    /**
     * Attempts to automatically initiate GATT connections to all currently bonded BLE devices.
     *
     * This function filters for devices that are:
     * - Bonded (i.e., previously paired at the system level)
     * - Bluetooth Low Energy (LE) devices only ([BluetoothDevice.DEVICE_TYPE_LE])
     *
     * For each bonded device, [BleGattManager.connect] is called to initiate the connection.
     * This is typically used during application startup to restore connections to known devices.
     *
     * Requires [Manifest.permission.BLUETOOTH_CONNECT] on Android 12+.
     *
     * @see getBondedDevices
     * @see BleGattManager.connect
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun autoConnectBondedDevices() {
        val bondedDevices = getBondedDevices()
        bondedDevices.forEach { device ->
            Log.i("BleBondManager", "🔄 Auto-connecting to bonded device: ${device.address}")
            gattManager.connect(device.address, autoConnect = true)
        }
    }

    /**
     * Retrieves a list of all bonded (paired) Bluetooth Low Energy (BLE) devices from the system.
     *
     * Only devices with [BluetoothDevice.DEVICE_TYPE_LE] (i.e., true BLE devices) are included.
     * The result is sorted alphabetically by device name; if the name is null, the address is used.
     *
     * Requires [Manifest.permission.BLUETOOTH_CONNECT] on Android 12+.
     *
     * @return A sorted list of bonded BLE devices.
     *
     * @see BluetoothAdapter.getBondedDevices
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun getBondedDevices(): List<BluetoothDevice> {
        return adapter.bondedDevices
            .filter { it.type == BluetoothDevice.DEVICE_TYPE_LE }
            .sortedBy { it.name ?: it.address }
    }

    /**
     * BroadcastReceiver that listens for system-level BLE bond state changes
     * and automatically connects to the device upon successful bonding.
     *
     * Triggered by system broadcasts with action [BluetoothDevice.ACTION_BOND_STATE_CHANGED].
     *
     * Behavior:
     * - When a BLE device completes bonding (BOND_BONDED), the receiver emits a [BondState.Bonded]
     *   event and initiates a GATT connection via [BleGattManager.connect].
     * - When bonding fails or is removed (BOND_NONE), emits [BondState.Unbonded].
     * - Bonding in progress (BOND_BONDING) is ignored by default.
     *
     * This receiver must be registered via [registerReceiver] to function.
     * Typically, registration is done in [MainActivity] or [Application] using `bleBondManager.registerReceiver()`.
     *
     * @see BluetoothDevice.ACTION_BOND_STATE_CHANGED
     * @see BleBondManager.registerReceiver
     * @see BleBondManager.bondStateFlow
     * @see BleGattManager.connect
     */
    private val bondReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            if (device == null) return

            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)

            when (bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    _bondStateFlow.tryEmit(BondState.Bonded(device))
                    Log.i("BleBondManager", "✅ Bonded: ${device.address}")
                    gattManager.connect(device.address) // ⬅️ 자동 GATT 연결
                }
                BluetoothDevice.BOND_NONE -> {
                    _bondStateFlow.tryEmit(BondState.Unbonded(device))
                    Log.w("BleBondManager", "❌ Unbonded: ${device.address}")
                }
                BluetoothDevice.BOND_BONDING -> {
                    // Optional: bonding in progress (not emitted)
                }
            }
        }
    }
}
