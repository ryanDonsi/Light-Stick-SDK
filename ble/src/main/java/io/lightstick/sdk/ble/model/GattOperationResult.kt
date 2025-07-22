package io.lightstick.sdk.ble.model

/**
 * Represents the result of a GATT operation (read, write, notify, etc.).
 *
 * This sealed class allows distinguishing between success and failure cases with detailed reasoning.
 * It provides a consistent return structure for BLE operations such as characteristic reads, writes, and descriptor updates.
 */
sealed class GattOperationResult {

    /**
     * Represents a successful GATT operation.
     *
     * @param address The MAC address of the BLE device involved in the operation.
     * @param operation A descriptive string identifying the operation (e.g., "readBattery", "sendLedColor").
     */
    data class Success(
        val address: String,
        val operation: String
    ) : GattOperationResult()

    /**
     * Represents a failed GATT operation with a reason for the failure.
     *
     * @param address The MAC address of the BLE device involved in the operation.
     * @param operation A descriptive string identifying the failed operation.
     * @param reason An enum value from [Reason] describing the specific cause of the failure.
     */
    data class Failure(
        val address: String,
        val operation: String,
        val reason: Reason
    ) : GattOperationResult()

    /**
     * Standardized reasons for GATT operation failures.
     *
     * These are intended to help SDK users identify and handle common BLE communication issues.
     */
    enum class Reason {
        /** BluetoothGatt object was not available for the given device. */
        GattUnavailable,

        /** The GATT service UUID was not found on the device. */
        ServiceNotFound,

        /** The GATT characteristic UUID was not found within the service. */
        CharacteristicNotFound,

        /** The GATT descriptor UUID was not found in the characteristic. */
        DescriptorNotFound,

        /** Client Characteristic Configuration Descriptor (CCCD) was not found. */
        CccdNotFound,

        /** The readCharacteristic() call returned false or failed. */
        ReadFailed,

        /** The writeCharacteristic() call failed or returned false. */
        WriteFailed,

        /** The descriptor write operation failed. */
        DescriptorWriteFailed,

        /** setCharacteristicNotification() returned false. */
        EnableNotificationFailed,

        /** The operation was rejected or aborted due to GATT state. */
        ExecuteRejected,

        /** The OTA process was aborted due to a notification or error. */
        OtaAborted,

        /** An unknown or unspecified error occurred. */
        Unknown
    }
}
