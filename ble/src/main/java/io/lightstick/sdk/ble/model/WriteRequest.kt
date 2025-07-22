package io.lightstick.sdk.ble.model

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import io.lightstick.sdk.ble.manager.WriteQueueManager

/**
 * Represents a BLE write request to be enqueued in a write queue.
 *
 * This sealed class provides a unified interface for both characteristic and descriptor writes.
 * These requests are typically processed by [WriteQueueManager] to ensure sequential BLE operations.
 *
 * @see CharacteristicWrite
 * @see DescriptorWrite
 */
sealed class WriteRequest {

    /**
     * The [BluetoothGatt] instance associated with the request.
     */
    abstract val gatt: BluetoothGatt

    /**
     * Represents a write request targeting a GATT characteristic.
     *
     * @property characteristic The [BluetoothGattCharacteristic] to write to.
     * @property data The payload to write to the characteristic.
     * @property writeType The write type, such as [BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT] or [BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE].
     *
     * @constructor Constructs a [CharacteristicWrite] request with the target characteristic, data, and write type.
     *
     * @sample
     * ```kotlin
     * val request = WriteRequest.createForCharacteristic(
     *     gatt, characteristic, byteArrayOf(0x01, 0x02), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
     * )
     * ```
     */
    class CharacteristicWrite(
        override val gatt: BluetoothGatt,
        val characteristic: BluetoothGattCharacteristic,
        val data: ByteArray,
        val writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ) : WriteRequest() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CharacteristicWrite) return false
            return gatt == other.gatt &&
                    characteristic == other.characteristic &&
                    data.contentEquals(other.data) &&
                    writeType == other.writeType
        }

        override fun hashCode(): Int {
            var result = gatt.hashCode()
            result = 31 * result + characteristic.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + writeType
            return result
        }
    }

    /**
     * Represents a write request targeting a GATT descriptor.
     *
     * @property descriptor The [BluetoothGattDescriptor] to write to.
     * @property data The payload to write to the descriptor.
     *
     * @constructor Constructs a [DescriptorWrite] request with the target descriptor and data.
     *
     * @sample
     * ```kotlin
     * val request = WriteRequest.createForDescriptor(
     *     gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
     * )
     * ```
     */
    class DescriptorWrite(
        override val gatt: BluetoothGatt,
        val descriptor: BluetoothGattDescriptor,
        val data: ByteArray
    ) : WriteRequest() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DescriptorWrite) return false
            return gatt == other.gatt &&
                    descriptor == other.descriptor &&
                    data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = gatt.hashCode()
            result = 31 * result + descriptor.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    companion object {

        /**
         * Factory method to create a [CharacteristicWrite] request.
         *
         * @param gatt The [BluetoothGatt] connection.
         * @param characteristic The target characteristic.
         * @param data The byte array to write.
         * @param writeType Optional write type (default = WRITE_TYPE_DEFAULT).
         * @return A [WriteRequest] wrapping the characteristic write.
         *
         * @sample
         * ```kotlin
         * WriteRequest.createForCharacteristic(
         *     gatt, characteristic, byteArrayOf(0x01, 0x02), WRITE_TYPE_NO_RESPONSE
         * )
         * ```
         */
        fun createForCharacteristic(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            data: ByteArray,
            writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ): WriteRequest = CharacteristicWrite(gatt, characteristic, data, writeType)

        /**
         * Factory method to create a [DescriptorWrite] request.
         *
         * @param gatt The [BluetoothGatt] connection.
         * @param descriptor The descriptor to write to.
         * @param value The value to be written to the descriptor.
         * @return A [WriteRequest] wrapping the descriptor write.
         *
         * @sample
         * ```kotlin
         * WriteRequest.createForDescriptor(
         *     gatt, descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
         * )
         * ```
         */
        fun createForDescriptor(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            value: ByteArray
        ): WriteRequest = DescriptorWrite(gatt, descriptor, value)
    }
}
