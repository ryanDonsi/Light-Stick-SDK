package com.lightstick.device.dto

/**
 * Snapshot of the standard Device Information Service (DIS).
 *
 * This immutable DTO aggregates commonly available information from the
 * standard GATT Device Information Service characteristics.
 *
 * Some fields may be null depending on the device implementation or
 * which characteristics were successfully read.
 *
 * It can be used as a convenience container when multiple reads are
 * performed together (e.g., read all DIS fields in one request).
 *
 * @param deviceName The device name (DIS 2A00), or null if unavailable.
 * @param modelNumber The model number (DIS 2A24), or null if unavailable.
 * @param firmwareRevision The firmware revision (DIS 2A26), or null if unavailable.
 * @param manufacturer The manufacturer name (DIS 2A29), or null if unavailable.
 * @return A new immutable [DeviceInfo] instance.
 * @throws None This data class does not throw any exceptions on construction.
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.DeviceSamples.sampleReadDeviceInfo
 */
data class DeviceInfo(
    val deviceName: String? = null,
    val modelNumber: String? = null,
    val firmwareRevision: String? = null,
    val manufacturer: String? = null
)
