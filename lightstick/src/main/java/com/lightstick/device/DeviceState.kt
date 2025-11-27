package com.lightstick.device

/**
 * Unified state container combining connection state and device information.
 *
 * This data class provides a complete snapshot of a device's current state,
 * making it easy to observe and react to changes in both connection status
 * and device information.
 *
 * Example usage:
 * ```
 * LSBluetooth.observeDeviceStates().collect { states ->
 *     states.forEach { (mac, state) ->
 *         println("Device $mac:")
 *         println("  Connection: ${state.connectionState}")
 *         println("  Battery: ${state.deviceInfo?.batteryLevel}%")
 *         println("  RSSI: ${state.deviceInfo?.rssi} dBm")
 *     }
 * }
 * ```
 *
 * @property macAddress Unique device identifier (MAC address)
 * @property connectionState Current connection lifecycle state
 * @property deviceInfo Device information (DIS, BAS, etc.), null if not yet read
 * @property lastSeenTimestamp Last time this device was observed (scan or connection event)
 *
 * @since 1.0.0
 */
data class DeviceState(
    val macAddress: String,
    val connectionState: ConnectionState,
    val deviceInfo: DeviceInfo? = null,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)