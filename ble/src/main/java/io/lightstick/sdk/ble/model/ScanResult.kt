package io.lightstick.sdk.ble.model

/**
 * Represents a single result from a BLE device scan.
 *
 * This class is typically populated during BLE discovery (e.g., `BluetoothLeScanner` callback)
 * and includes information about the remote device such as name, MAC address, and signal strength.
 *
 * @property name The advertised device name (from scan record), may be null if not broadcasted.
 *                This is commonly set using `setName()` or retrieved from the advertising payload.
 * @property address The MAC address of the BLE device (e.g., "00:11:22:33:44:55").
 * @property rssi The received signal strength indicator (RSSI) in dBm (e.g., -45 stronger, -90 weaker).
 *
 * @constructor Constructs a [ScanResult] containing basic advertising information from a BLE scan.
 *
 * @sample
 * ```kotlin
 * val result = ScanResult(
 *     name = "LightStick",
 *     address = "00:11:22:33:44:55",
 *     rssi = -58
 * )
 *
 * Log.d("BLE", "Discovered ${result.name} at ${result.address} (RSSI: ${result.rssi})")
 * ```
 */
data class ScanResult(
    val name: String?,
    val address: String,
    val rssi: Int
)
