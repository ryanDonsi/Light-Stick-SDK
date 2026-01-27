package com.lightstick.config

import com.lightstick.device.Device
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for SDK initialization behavior.
 *
 * This class encapsulates all initialization-time settings that control
 * how the LightStick SDK establishes connections, restores sessions, and
 * manages bonded devices. It provides fine-grained control over connection
 * policies, timeouts, and automatic connection behaviors.
 *
 * The configuration uses sensible defaults that work for most use cases,
 * but can be customized for specific application requirements such as
 * battery optimization, connection reliability, or user experience preferences.
 *
 * @property autoConnectBonded If true, automatically connect to all bonded (paired)
 *           devices during initialization. This provides a seamless user experience
 *           by restoring previously established connections.
 * @property bondedDeviceFilter Optional filter predicate to selectively connect
 *           only to specific bonded devices. If null, all bonded devices will be
 *           considered for connection (subject to [maxConcurrentConnections]).
 *           Example: `{ device -> device.name?.startsWith("LightStick") == true }`
 * @property connectionTimeout Maximum time to wait for each device connection
 *           attempt before considering it failed. Defaults to 10 seconds.
 * @property maxConcurrentConnections Maximum number of simultaneous BLE connections
 *           to establish. Android BLE stack typically supports 7 concurrent connections.
 *           Attempting more may result in connection failures or system instability.
 * @property restoreSystemConnections If true, scan for devices already connected
 *           at the system level (e.g., by other apps or previous sessions) and
 *           attempt to restore SDK-level sessions with them. This is useful for
 *           recovering connections after app restart or process death.
 * @property systemConnectionFilter Optional filter for system connection restoration.
 *            Only devices that pass this filter will be restored from system-level connections.
 *            If null, defaults to Light-Stick devices only (name contains "LS").
 *            Example: `{ name -> name?.contains("LS", ignoreCase = true) == true }`
 * @property autoReadDeviceInfo If true, automatically read Device Information Service
 *           (DIS) characteristics and battery level immediately after successful
 *           connection. This populates the DeviceInfo cache for faster UI updates.
 * @property enableConnectionMonitoring If true, actively monitor connection state
 *           changes and emit updates via StateFlow. This enables reactive UI
 *           updates but may have a slight impact on battery life.
 *
 * @since 1.1.0
 * @see InitResult
 * @sample com.lightstick.samples.BleSamples.sampleInitializeWithConfig
 */
data class InitConfig(
    val autoConnectBonded: Boolean = false,
    val bondedDeviceFilter: ((Device) -> Boolean)? = null,
    val connectionTimeout: Duration = 10.seconds,
    val maxConcurrentConnections: Int = 7,
    val restoreSystemConnections: Boolean = true,
    val systemConnectionFilter: ((String?) -> Boolean)? = null,
    val autoReadDeviceInfo: Boolean = true,
    val enableConnectionMonitoring: Boolean = true
)

/**
 * Result of SDK initialization operation.
 *
 * This class provides detailed feedback about what happened during SDK
 * initialization, including which devices were discovered, successfully
 * connected, or failed to connect. It allows the application to make
 * informed decisions about connection state and user feedback.
 *
 * The result is returned from [LSBluetooth.initializeAsync] and contains
 * separate lists for different connection scenarios:
 * - System-level connections that were already established
 * - Connections that were successfully restored or established
 * - Failed connection attempts with error details
 *
 * @property systemConnectedDevices List of devices that were found to be
 *           already connected at the Android BluetoothManager level when
 *           initialization began. These may include devices connected by
 *           other apps or from previous app sessions.
 * @property restoredDevices List of devices for which SDK-level sessions
 *           were successfully restored. These devices were previously connected
 *           and the SDK was able to re-establish GATT sessions with them.
 * @property autoConnectedDevices List of bonded devices that were automatically
 *           connected during initialization as specified by [InitConfig.autoConnectBonded].
 * @property failedConnections Map of devices that failed to connect, with the
 *           corresponding error/exception that caused the failure. This can be
 *           used for logging, debugging, or displaying error messages to users.
 *
 * @since 1.1.0
 * @see InitConfig
 * @see LSBluetooth.initializeAsync
 */
data class InitResult(
    val systemConnectedDevices: List<Device> = emptyList(),
    val restoredDevices: List<Device> = emptyList(),
    val autoConnectedDevices: List<Device> = emptyList(),
    val failedConnections: Map<String, Throwable> = emptyMap()
)