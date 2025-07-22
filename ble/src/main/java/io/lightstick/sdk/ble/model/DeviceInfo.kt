package io.lightstick.sdk.ble.model

/**
 * Represents detailed device information retrieved from the BLE Device Information Service (DIS).
 *
 * This class is populated after reading standard characteristics from the
 * `DEVICE_INFO_SERVICE`, such as manufacturer name, model number, firmware revision, and device name.
 *
 * Each property may be `null` if the corresponding characteristic was not available or failed to read.
 *
 * @property manufacturer Manufacturer name of the BLE device (e.g., "THINKWARE", "DONGSITECH").
 * @property model Model number or hardware identifier (e.g., "DBL-M100").
 * @property firmwareVersion Current firmware version running on the device (e.g., "1.2.3").
 * @property deviceName Logical name of the device, often user-defined or default (e.g., "LightStick").
 *
 * @constructor Creates a new [DeviceInfo] object to hold optional metadata retrieved over BLE GATT.
 *
 * @sample
 * ```kotlin
 * val info = DeviceInfo(
 *     manufacturer = "THINKWARE",
 *     model = "DBL-M100",
 *     firmwareVersion = "1.0.0",
 *     deviceName = "LightStick"
 * )
 *
 * Log.d("BLE", "Device Info: $info")
 * ```
 */
data class DeviceInfo(
    val manufacturer: String?,
    val model: String?,
    val firmwareVersion: String?,
    val deviceName: String?
)
