package io.lightstick.sdk.ble.delegate

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService

interface GattEventDelegate {

    /**
     * Called when a characteristic value is changed via notification or indication.
     */
    fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {}

    /**
     * Called when a characteristic read is completed.
     */
    fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {}

    /**
     * Called when a characteristic write is completed.
     */
    fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {}

    /**
     * Called when services are discovered.
     */
    fun onServicesDiscovered(
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>
    ) {}

    /**
     * Called when MTU size changes.
     */
    fun onMtuChanged(
        gatt: BluetoothGatt,
        mtu: Int,
        status: Int
    ) {}

    /**
     * Called when descriptor write is completed (if needed).
     */
    fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {}
}
