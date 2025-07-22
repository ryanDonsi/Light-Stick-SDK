package io.lightstick.sdk.ble.model

/**
 * Represents a bonded Bluetooth Low Energy (BLE) device.
 *
 * This data class is used to store minimal identifying information
 * for a BLE device that has previously completed bonding (pairing) with the system.
 *
 * @property name Optional display name of the device, typically provided by the remote device.
 * @property address MAC address of the BLE device (e.g., "00:11:22:33:AA:BB").
 *
 * This model is primarily used when retrieving bonded devices via the system Bluetooth API,
 * such as `BluetoothAdapter.getBondedDevices()`.
 */
data class BondedDevice(
    val name: String?,
    val address: String
)
