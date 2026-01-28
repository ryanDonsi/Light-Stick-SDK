package com.lightstick

import android.Manifest
import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import com.lightstick.config.DeviceFilter
import com.lightstick.config.DeviceFilterMapper.toInternal
import com.lightstick.device.ConnectionState
import com.lightstick.device.Device
import com.lightstick.device.DeviceInfo
import com.lightstick.device.DeviceState
import com.lightstick.device.TypeMappers
import com.lightstick.internal.api.Facade
import com.lightstick.types.Color
import com.lightstick.types.LSEffectPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Main entry point for the LightStick BLE SDK.
 *
 * This singleton provides:
 * - Initialization
 * - Scanning (discover devices)
 * - Global queries (connected/bonded counts & lists)
 * - Broadcast helpers (send color/effect to all connected)
 * - State observation (connection states, device states)
 * - Shutdown
 *
 * Individual device operations (connect, sendColor, sendEffect, etc.) should be performed
 * through the [Device] class after obtaining an instance via scan or manual construction.
 *
 * @since 1.0.0
 */
object LSBluetooth {

    private val scope = CoroutineScope(Dispatchers.Default)

    // ============================================================================================
    // Initialization
    // ============================================================================================

    /**
     * Initializes the SDK with the application context.
     *
     * Call this once during app startup (e.g., Application.onCreate).
     * Safe to call multiple times; only the first call has effect.
     *
     * @param context Application context.
     * @param deviceFilter Optional device filter configuration.
     *        If `null`, all devices are accepted (no filtering).
     *
     * Examples:
     * ```
     * // Accept all devices (default)
     * LSBluetooth.initialize(context)
     *
     * // Filter by device name
     * LSBluetooth.initialize(
     *     context,
     *     deviceFilter = DeviceFilter.byName("LS")
     * )
     *
     * // Filter by MAC address OUI
     * LSBluetooth.initialize(
     *     context,
     *     deviceFilter = DeviceFilter.byMacPrefix("AA:BB:CC")
     * )
     *
     * // Combined filters
     * val filter = DeviceFilter.byName("LS")
     *     .and(DeviceFilter.byMacPrefix("AA:BB:CC"))
     *     .and(DeviceFilter.byMinRssi(-70))
     * LSBluetooth.initialize(context, deviceFilter = filter)
     * ```
     *
     * @see DeviceFilter
     */
    @JvmStatic
    @MainThread
    fun initialize(
        context: Context,
        deviceFilter: DeviceFilter? = null,
        allowUnknownDevices: Boolean = false
    ) {
        Facade.initialize(context, deviceFilter?.toInternal(), allowUnknownDevices)
    }

    // ============================================================================================
    // Scan
    // ============================================================================================

    /**
     * Starts BLE scanning with optional timeout.
     *
     * @param scanTimeSeconds Scan duration in seconds (default: 3, range: 1-300).
     * @param onFound Callback invoked for each discovered device.
     *
     * Examples:
     * ```
     * // Default 3 seconds
     * LSBluetooth.startScan { device ->
     *     println("Found: ${device.name}")
     * }
     *
     * // Custom 10 seconds
     * LSBluetooth.startScan(scanTimeSeconds = 10) { device ->
     *     println("Found: ${device.name}")
     * }
     *
     * // Long scan 30 seconds
     * LSBluetooth.startScan(30) { device ->
     *     println("Found: ${device.name}")
     * }
     * ```
     */
    @JvmStatic
    @JvmOverloads
    @MainThread
    @RequiresPermission(
        anyOf = [
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ]
    )
    fun startScan(
        scanTimeSeconds: Int = 3,  // ✅ 추가
        onFound: (Device) -> Unit
    ) {
        Facade.startScan(scanTimeSeconds) { mac, name, rssi ->
            onFound(Device(mac, name, rssi))
        }
    }

    /**
     * Stops BLE scanning.
     *
     * @throws SecurityException If scan permissions are missing.
     */
    @JvmStatic
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        Facade.stopScan()
    }

    // ============================================================================================
    // Global Queries (Connected/Bonded Lists & Counts)
    // ============================================================================================

    /**
     * Returns the list of currently connected devices.
     *
     * @return List of connected Device objects.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted.
     */
    @JvmStatic
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectedDevices(): List<Device> =
        Facade.connectedList().map { (mac, name, rssi) ->
            Device(mac, name, rssi)
        }

    /**
     * Returns the number of currently connected devices.
     *
     * @return Connected device count.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted.
     */
    @JvmStatic
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectedCount(): Int = Facade.connectedCount()

    /**
     * Returns the system-bonded (paired) device list.
     *
     * @return List of bonded Device objects (RSSI is null).
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted.
     */
    @JvmStatic
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun bondedDevices(): List<Device> =
        Facade.bondedList().map { (mac, name) ->
            Device(mac, name, rssi = null)
        }

    /**
     * Returns the number of system-bonded (paired) devices.
     *
     * @return Bonded device count.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted.
     */
    @JvmStatic
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun bondedCount(): Int = Facade.bondedCount()

    // ============================================================================================
    // Broadcast Helpers
    // ============================================================================================

    /**
     * Sends the same color to all currently connected devices.
     *
     * @param color RGB color.
     * @param transition Transition time parameter (firmware-specific).
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted.
     */
    @JvmStatic
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun broadcastColor(color: Color, transition: Int) {
        val packet = byteArrayOf(
            color.r.toByte(),
            color.g.toByte(),
            color.b.toByte(),
            transition.toByte()
        )
        Facade.sendColorPacket(packet)
    }

    /**
     * Sends the same effect payload to all currently connected devices.
     *
     * @param payload 20-byte effect payload.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted.
     */
    @JvmStatic
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun broadcastEffect(payload: LSEffectPayload) {
        Facade.sendEffectPayload(payload.toByteArray())
    }

    /**
     * Plays the same timestamped frames on all currently connected devices.
     *
     * @param frames List of (timestampMs, 20-byte payload) pairs.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted.
     */
    @JvmStatic
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun broadcastFrames(frames: List<Pair<Long, LSEffectPayload>>) {
        val converted = frames.map { (ts, payload) -> ts to payload.toByteArray() }
        Facade.playAllEntries(converted)
    }

    // ============================================================================================
    // State Observation
    // ============================================================================================

    /**
     * Observes unified device states (connection + device info).
     *
     * @return Hot StateFlow of device states mapped by MAC address.
     */
    @JvmStatic
    fun observeDeviceStates(): StateFlow<Map<String, DeviceState>> {
        return Facade.getInternalDeviceStates()
            .map { internalMap ->
                internalMap.mapValues { (_, internalState) ->
                    TypeMappers.toPublic(internalState)
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyMap()
            )
    }

    /**
     * Observes connection states only.
     *
     * @return Hot StateFlow of connection states mapped by MAC address.
     */
    @JvmStatic
    fun observeConnectionStates(): StateFlow<Map<String, ConnectionState>> {
        return Facade.getInternalConnectionStates()
            .map { internalMap ->
                internalMap.mapValues { (_, internalState) ->
                    TypeMappers.toPublic(internalState)
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyMap()
            )
    }

    /**
     * Returns cached device info snapshot for a specific device.
     *
     * @param mac Device MAC address.
     * @return DeviceInfo if available, null otherwise.
     */
    @JvmStatic
    fun getCachedDeviceInfo(mac: String): DeviceInfo? {
        return Facade.getInternalDeviceInfo(mac)?.let { TypeMappers.toPublic(it) }
    }

    // ============================================================================================
    // Shutdown
    // ============================================================================================

    /**
     * Disconnects all devices and releases SDK resources.
     *
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted.
     */
    @JvmStatic
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun shutdown() {
        Facade.shutdown()
    }
}