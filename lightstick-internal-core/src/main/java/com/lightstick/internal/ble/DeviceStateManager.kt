package com.lightstick.internal.ble

//import android.bluetooth.BluetoothAdapter
//import android.bluetooth.BluetoothManager
//import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.lightstick.internal.ble.state.InternalConnectionState
import com.lightstick.internal.ble.state.InternalDeviceInfo
import com.lightstick.internal.ble.state.InternalDeviceState
import com.lightstick.internal.ble.state.InternalDeviceStateEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap


/**
 * Centralized manager for BLE device state tracking.
 */
internal class DeviceStateManager(
    private val context: Context,
    private val deviceFilter: ((String, String?, Int?) -> Boolean)? = null
) {

    private val TAG = "DeviceStateManager"

//    private val bluetoothManager: BluetoothManager? =
//        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
//    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val connectionStatesMap = ConcurrentHashMap<String, InternalConnectionState>()
    private val deviceInfoMap = ConcurrentHashMap<String, InternalDeviceInfo>()
    private val deviceNamesMap = ConcurrentHashMap<String, String>()
    private val deviceRssiMap = ConcurrentHashMap<String, Int>()

    private val _connectionStates = MutableStateFlow<Map<String, InternalConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, InternalConnectionState>> = _connectionStates.asStateFlow()

    private val _deviceStates = MutableStateFlow<Map<String, InternalDeviceState>>(emptyMap())
    val deviceStates: StateFlow<Map<String, InternalDeviceState>> = _deviceStates.asStateFlow()

    private val _Internal_deviceStateEvents = MutableSharedFlow<InternalDeviceStateEvent>(
        extraBufferCapacity = 8
    )
    val internalDeviceStateEvents: SharedFlow<InternalDeviceStateEvent> = _Internal_deviceStateEvents.asSharedFlow()



//    init {
//        detectSystemConnectedDevices()
//    }

    // ============================================================================================
    // Public API - State Updates
    // ============================================================================================

//    fun updateConnectionState(macAddress: String, state: InternalConnectionState) {
//        connectionStatesMap[macAddress] = state
//        emitConnectionStates()
//        rebuildAndEmitDeviceStates()
//    }
    fun updateConnectionState(macAddress: String, state: InternalConnectionState) {
        connectionStatesMap[macAddress] = state
        _Internal_deviceStateEvents.tryEmit(InternalDeviceStateEvent(mac = macAddress, state = state))
        Log.d(TAG, "📤 DeviceStateEvent emitted: $macAddress → $state")
        emitConnectionStates()
        rebuildAndEmitDeviceStates()
    }

    fun removeConnectionState(macAddress: String) {
        connectionStatesMap.remove(macAddress)
        deviceInfoMap.remove(macAddress)
        deviceNamesMap.remove(macAddress)
        deviceRssiMap.remove(macAddress)
        emitConnectionStates()
        rebuildAndEmitDeviceStates()
    }

    fun updateDeviceInfo(macAddress: String, info: InternalDeviceInfo) {
        deviceInfoMap[macAddress] = info
        rebuildAndEmitDeviceStates()
    }

    fun updateDeviceName(macAddress: String, name: String?) {
        if (name != null) {
            deviceNamesMap[macAddress] = name
        } else {
            deviceNamesMap.remove(macAddress)
        }

        deviceInfoMap[macAddress]?.let { existing ->
            deviceInfoMap[macAddress] = existing.copy(deviceName = name)
            rebuildAndEmitDeviceStates()
        }
    }

    fun updateDeviceRssi(macAddress: String, rssi: Int?) {
        if (rssi != null) {
            deviceRssiMap[macAddress] = rssi
        } else {
            deviceRssiMap.remove(macAddress)
        }

        deviceInfoMap[macAddress]?.let { existing ->
            deviceInfoMap[macAddress] = existing.copy(rssi = rssi)
            rebuildAndEmitDeviceStates()
        }
    }

    fun removeDevice(macAddress: String) {
        connectionStatesMap.remove(macAddress)
        deviceInfoMap.remove(macAddress)
        deviceNamesMap.remove(macAddress)
        deviceRssiMap.remove(macAddress)
        emitConnectionStates()
        rebuildAndEmitDeviceStates()
    }

    fun clearAll() {
        connectionStatesMap.clear()
        deviceInfoMap.clear()
        deviceNamesMap.clear()
        deviceRssiMap.clear()
        emitConnectionStates()
        rebuildAndEmitDeviceStates()
    }

    // ============================================================================================
    // Public API - State Queries
    // ============================================================================================

    fun getConnectionState(macAddress: String): InternalConnectionState? {
        return connectionStatesMap[macAddress]
    }

    fun getDeviceInfo(macAddress: String): InternalDeviceInfo? {
        return deviceInfoMap[macAddress]
    }

    fun getDeviceState(macAddress: String): InternalDeviceState? {
        val connectionState = connectionStatesMap[macAddress] ?: return null
        return InternalDeviceState(
            macAddress = macAddress,
            connectionState = connectionState,
            deviceInfo = deviceInfoMap[macAddress],
            lastSeenTimestamp = System.currentTimeMillis()
        )
    }

    // ============================================================================================
    // Private Helpers - Flow Emission
    // ============================================================================================

    private fun emitConnectionStates() {
        val current = connectionStatesMap.toMap()
        if (_connectionStates.value != current) {
            _connectionStates.value = current
        }
    }

    private fun rebuildAndEmitDeviceStates() {
        val unified = connectionStatesMap
            .filter { (mac, _) ->
                val name = deviceNamesMap[mac]
                val rssi = deviceRssiMap[mac]
                deviceFilter?.invoke(mac, name, rssi) ?: true
            }
            .mapValues { (mac, connectionState) ->
                InternalDeviceState(
                    macAddress = mac,
                    connectionState = connectionState,
                    deviceInfo = deviceInfoMap[mac],
                    lastSeenTimestamp = System.currentTimeMillis()
                )
            }

        if (_deviceStates.value != unified) {
            _deviceStates.value = unified
        }
    }

    // ============================================================================================
    // Private Helpers - System-Level Connection Detection
    // ============================================================================================

    /**
     * Detects devices already connected at the system level on initialization.
     *
     * Applies the global device filter to only restore devices that pass the filter.
     * If no filter is set, all system-connected devices are restored.
     */
//    private fun detectSystemConnectedDevices() {
//        val connectedDevices = try {
//            bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
//        } catch (e: SecurityException) {
//            Log.w(TAG, "Permission denied for getConnectedDevices")
//            emptyList()
//        } catch (e: Exception) {
//            Log.e(TAG, "Error getting connected devices: ${e.message}")
//            emptyList()
//        }
//        if (connectedDevices.isEmpty()) {
//            Log.d(TAG, "No system-level connected devices found")
//            return
//        }
//
//        var hasIncluded = false
//
//        connectedDevices.forEach { device ->
//            val mac = device.address
//            val name = try {
//                device.name
//            } catch (e: SecurityException) {
//                null
//            }
//
//            val shouldInclude = deviceFilter?.invoke(mac, name, null) ?: true
//
//            if (shouldInclude) {
//                connectionStatesMap[mac] = InternalConnectionState.Connected()
//                if (name != null) {
//                    deviceNamesMap[mac] = name
//                }
//                Log.d(TAG, "include: $mac (${name ?: "Unknown"})")
//                hasIncluded = true
//            } else {
//                Log.d(TAG, "filtered: $mac (${name ?: "Unknown"})")
//            }
//        }
//
//        if (hasIncluded) {
//            emitConnectionStates()
//            rebuildAndEmitDeviceStates()
//        }
//    }
}