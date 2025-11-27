package com.lightstick.internal.ble.state

/**
 * Internal representation of complete device information.
 *
 * This data class aggregates all available device information from:
 * - Device Information Service (DIS)
 * - Battery Service (BAS)
 * - Custom MAC characteristic
 * - Connection metadata (RSSI, connection status)
 *
 * Internal visibility ensures this type is only used within the internal module
 * and converted to public types at the API boundary.
 *
 * @property deviceName Device name from DIS (2A00)
 * @property modelNumber Model number from DIS (2A24)
 * @property firmwareRevision Firmware revision from DIS (2A26)
 * @property manufacturer Manufacturer name from DIS (2A29)
 * @property batteryLevel Battery level percentage (0-100), null if unavailable
 * @property macAddress Device MAC address
 * @property isConnected Current connection status
 * @property rssi Signal strength (RSSI) in dBm, null if unavailable
 * @property lastUpdated Timestamp when this info was last updated (system time in milliseconds)
 */
data class InternalDeviceInfo(
    val deviceName: String? = null,
    val modelNumber: String? = null,
    val firmwareRevision: String? = null,
    val manufacturer: String? = null,
    val batteryLevel: Int? = null,
    val macAddress: String,
    val isConnected: Boolean = false,
    val rssi: Int? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)