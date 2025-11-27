package com.lightstick.device

/**
 * Enhanced snapshot of device information.
 *
 * This immutable DTO aggregates all available device information including:
 * - Device Information Service (DIS) characteristics
 * - Battery Service (BAS) level
 * - Connection metadata (MAC, RSSI, connection status)
 * - Last update timestamp
 *
 * @property deviceName The device name (DIS 2A00), or null if unavailable
 * @property modelNumber The model number (DIS 2A24), or null if unavailable
 * @property firmwareRevision The firmware revision (DIS 2A26), or null if unavailable
 * @property manufacturer The manufacturer name (DIS 2A29), or null if unavailable
 * @property batteryLevel Battery level percentage (0-100), or null if unavailable
 * @property macAddress Device MAC address
 * @property rssi Signal strength (RSSI) in dBm, or null if unavailable
 * @property isConnected Whether the device is currently connected
 * @property lastUpdated Timestamp when this info was last updated (system time in milliseconds)
 *
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.DeviceSamples.sampleReadDeviceInfo
 */
data class DeviceInfo(
    val deviceName: String? = null,
    val modelNumber: String? = null,
    val firmwareRevision: String? = null,
    val manufacturer: String? = null,
    val batteryLevel: Int? = null,
    val macAddress: String,
    val rssi: Int? = null,
    val isConnected: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)