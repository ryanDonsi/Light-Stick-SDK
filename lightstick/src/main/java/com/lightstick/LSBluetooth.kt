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
import com.lightstick.device.DeviceInfoResult
import com.lightstick.device.DeviceState
import com.lightstick.device.TypeMappers
import com.lightstick.internal.api.Facade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.shareIn

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

    // Cached StateFlows — created once so SharingStarted.Eagerly has time to collect the
    // current source value before any ViewModel subscribes, avoiding the race where a freshly
    // created stateIn(initialValue = emptyMap()) is observed before its coroutine runs.
    // initialValue is seeded from the synchronous snapshot so that calls made *after* DIS
    // completes also return correct state immediately.
    private val cachedDeviceStates: StateFlow<Map<String, DeviceState>> by lazy {
        val snapshot = Facade.getInternalDeviceStatesSnapshot()
            .mapValues { (_, s) -> TypeMappers.toPublic(s) }
        Facade.getInternalDeviceStates()
            .map { internalMap ->
                internalMap.mapValues { (_, internalState) ->
                    TypeMappers.toPublic(internalState)
                }
            }
            .stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = snapshot)
    }

    private val cachedConnectionStates: StateFlow<Map<String, ConnectionState>> by lazy {
        val snapshot = Facade.getInternalConnectionStatesSnapshot()
            .mapValues { (_, s) -> TypeMappers.toPublic(s) }
        Facade.getInternalConnectionStates()
            .map { internalMap ->
                internalMap.mapValues { (_, internalState) ->
                    TypeMappers.toPublic(internalState)
                }
            }
            .stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = snapshot)
    }

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
        Facade.restoreSystemConnectedDevices()
    }

    /**
     * Turns BLE debug logging on/off — every packet write (color, effect, group, game command)
     * and its raw bytes, plus connection/service-discovery/OTA diagnostics, all logged under
     * the `Lightstick` tag. Off by default in this build; call this to enable it while
     * developing or diagnosing an issue, and turn it back off for production.
     *
     * Safe to call any time (not just at startup), from any thread.
     *
     * @param enabled `true` to log, `false` to silence.
     */
    @JvmStatic
    fun setDebugLoggingEnabled(enabled: Boolean) {
        Facade.setDebugLoggingEnabled(enabled)
    }

    // ============================================================================================
    // GATT command interval
    // ============================================================================================

    /** Default minimum interval between GATT commands per device, in milliseconds (50). */
    const val DEFAULT_COMMAND_INTERVAL_MS: Long = com.lightstick.internal.ble.queue.CommandInterval.DEFAULT_MS

    /** Largest value accepted by [setCommandIntervalMs], in milliseconds (1000). */
    const val MAX_COMMAND_INTERVAL_MS: Long = com.lightstick.internal.ble.queue.CommandInterval.MAX_MS

    /**
     * Sets the minimum interval between completed GATT commands, **per device**
     * (default [DEFAULT_COMMAND_INTERVAL_MS] = 50 ms).
     *
     * Each connected device has one serialized command queue; after a command completes the next
     * one is held back until this interval has passed. It therefore throttles FF02 effect/timeline
     * frames, and also FF03 game commands and FF0A control. **Reads (DIS/BAS), CCCD writes and OTA
     * firmware writes are exempt** — they pace themselves by waiting for the device's response/ack,
     * and the interval would only slow connection setup and updates down (the device aborts an OTA
     * that runs past its total time limit, result 0x0D). A larger value spaces frames further apart
     * (more loss margin, more delay; a full queue of 64 drops its oldest pending command); `0`
     * removes the minimum entirely.
     *
     * Applies immediately to devices that are already connected or connecting, and to future
     * connections. The value is not persisted — set it again after the process restarts. Safe to
     * call any time, from any thread, even before [initialize].
     *
     * @param intervalMs Interval in milliseconds, `0..[MAX_COMMAND_INTERVAL_MS]`.
     * @throws IllegalArgumentException If [intervalMs] is outside that range.
     * @since 2.200.0
     */
    @JvmStatic
    fun setCommandIntervalMs(intervalMs: Long) {
        Facade.setCommandIntervalMs(intervalMs)
    }

    /** Current minimum interval between GATT commands in milliseconds. @since 2.200.0 */
    @JvmStatic
    fun getCommandIntervalMs(): Long = Facade.commandIntervalMs()

    /** Default maximum number of pending GATT commands per device (8). */
    const val DEFAULT_COMMAND_QUEUE_SIZE: Int = com.lightstick.internal.ble.queue.CommandInterval.DEFAULT_QUEUE_SIZE

    /** Largest value accepted by [setCommandQueueSize] (256). */
    const val MAX_COMMAND_QUEUE_SIZE: Int = com.lightstick.internal.ble.queue.CommandInterval.MAX_QUEUE_SIZE

    /**
     * Sets the maximum number of **pending** GATT commands per device
     * (default [DEFAULT_COMMAND_QUEUE_SIZE] = 8, the command currently in flight not counted).
     *
     * When more commands are waiting than this, the **oldest pending commands are dropped**: they
     * are never executed, and commands with a result callback (reads, notification setup, OTA
     * writes) are failed with `CommandAbortedException`. Timeline frames and single effect/color
     * sends barely use the queue — a newer frame replaces one still waiting. Commands sent faster
     * than the [setCommandIntervalMs] interval allows (e.g. `playEffects` sequences, FF03/FF0A)
     * back up by roughly `queueSize x interval` (8 x 50 ms = 400 ms by default) before dropping. A small queue keeps the LED close to
     * real time; a larger one preserves more frames at the cost of delay. Apps that fire many GATT
     * operations at once (parallel reads right after connect, bursts of sends) should raise it.
     *
     * Read on every enqueue, so it applies immediately to connected devices; lowering it trims the
     * queue on the next enqueue. Not persisted. Safe to call any time, even before [initialize].
     *
     * @param size `1..[MAX_COMMAND_QUEUE_SIZE]`.
     * @throws IllegalArgumentException If [size] is outside that range.
     * @since 2.200.0
     */
    @JvmStatic
    fun setCommandQueueSize(size: Int) {
        Facade.setCommandQueueSize(size)
    }

    /** Current maximum number of pending GATT commands per device. @since 2.200.0 */
    @JvmStatic
    fun getCommandQueueSize(): Int = Facade.commandQueueSize()

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
            val device = Device(mac, name, rssi)
            MainThreadCallbacks.run { onFound(device) }
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
    // State Observation
    // ============================================================================================

    data class DeviceStateEvent(
        val mac:   String,
        val state: ConnectionState
    )

    // Created once: building a new shareIn() per call left one more collector running forever in
    // [scope] for every observeDeviceStateEvents() call.
    private val cachedDeviceStateEvents: SharedFlow<DeviceStateEvent> by lazy {
        Facade.getDeviceStateEvents()
            .map { event ->
                DeviceStateEvent(
                    mac   = event.mac,
                    state = TypeMappers.toPublic(event.state)
                )
            }
            .shareIn(
                scope   = scope,
                started = SharingStarted.Eagerly,
                replay  = 0
            )
    }

    @JvmStatic
    fun observeDeviceStateEvents(): SharedFlow<DeviceStateEvent> = cachedDeviceStateEvents

    /**
     * Observes unified device states (connection + device info).
     *
     * @return Hot StateFlow of device states mapped by MAC address.
     */
    @JvmStatic
    fun observeDeviceStates(): StateFlow<Map<String, DeviceState>> = cachedDeviceStates

    /**
     * Observes connection states only.
     *
     * @return Hot StateFlow of connection states mapped by MAC address.
     */
    @JvmStatic
    fun observeConnectionStates(): StateFlow<Map<String, ConnectionState>> = cachedConnectionStates

    /**
     * Returns cached device info snapshot for a specific device.
     *
     * Under normal usage the SDK reads DIS automatically before delivering the
     * [Device.connect] onConnected callback, so [DeviceInfoResult.Available] is
     * expected whenever this is called from within or after that callback.
     *
     * @param mac Device MAC address.
     * @return [DeviceInfoResult.Available] with full info, or [DeviceInfoResult.Error]
     *         with a specific [DeviceInfoResult.ErrorCode] explaining why info is absent.
     */
    @JvmStatic
    fun getCachedDeviceInfo(mac: String): DeviceInfoResult {
        if (!Facade.isConnected(mac)) {
            return DeviceInfoResult.Error(DeviceInfoResult.ErrorCode.NOT_CONNECTED)
        }
        val info = Facade.getInternalDeviceInfo(mac)
            ?: return DeviceInfoResult.Error(DeviceInfoResult.ErrorCode.INFO_NOT_READY)
        return DeviceInfoResult.Available(TypeMappers.toPublic(info))
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