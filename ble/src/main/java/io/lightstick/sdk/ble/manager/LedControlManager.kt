package io.lightstick.sdk.ble.manager

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import androidx.annotation.RequiresPermission
import io.lightstick.sdk.ble.delegate.GattEventDelegate
import io.lightstick.sdk.ble.model.GattOperationResult
import io.lightstick.sdk.ble.model.LSEffectPayload
import io.lightstick.sdk.ble.model.LedColor
import io.lightstick.sdk.ble.util.UuidConstants
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Manager for controlling LED color and effect over BLE GATT.
 *
 * This class provides high-level APIs to control LED behavior on BLE devices.
 * Internally, it uses [BleGattManager] for GATT communication and emits operation results through Flows.
 *
 * It supports both raw byte and structured [LedColor], [LSEffectPayload] based operations.
 */
class LedControlManager(
    private val gattManager: BleGattManager
) : GattEventDelegate {

    init {
        gattManager.addDelegate(this)
    }

    /**
     * Emits the result of LED color write operation.
     *
     * Each emission is a pair: `(deviceAddress: String, success: Boolean)`.
     * - `true` indicates a successful write to the LED color characteristic.
     * - `false` indicates failure (e.g., GATT error, disconnected).
     *
     * Typically used in the UI or ViewModel layer to display operation result.
     *
     * @sample
     * launch {
     *     ledControlManager.ledColorWriteFlow.collect { (address, success) ->
     *         if (success) {
     *             Log.d("LED", "Color write to $address succeeded")
     *         } else {
     *             Log.e("LED", "Color write to $address failed")
     *         }
     *     }
     * }
     */
    val ledColorWriteFlow: MutableSharedFlow<Pair<String, Boolean>> = MutableSharedFlow()

    /**
     * Emits the result of LED effect write operation.
     *
     * Each emission is a pair: `(deviceAddress: String, success: Boolean)`.
     * - `true` indicates successful effect data write.
     * - `false` indicates failure (e.g., characteristic not found or GATT write error).
     *
     * This can be observed to show effect apply status in the UI.
     *
     * @sample
     * launch {
     *     ledControlManager.ledEffectWriteFlow.collect { (address, success) ->
     *         if (success) {
     *             Log.i("LED", "Effect write to $address succeeded")
     *         } else {
     *             Log.w("LED", "Effect write to $address failed")
     *         }
     *     }
     * }
     */
    val ledEffectWriteFlow: MutableSharedFlow<Pair<String, Boolean>> = MutableSharedFlow()


    /**
     * Sends an LED color command to the BLE device using RGB + transition format.
     *
     * @param address MAC address of the target BLE device
     * @param red Red intensity (0–255)
     * @param green Green intensity (0–255)
     * @param blue Blue intensity (0–255)
     * @param transition Transition duration or speed (0–255), default = 0
     * @return [GattOperationResult] indicating success or failure of the write operation
     *
     * @throws SecurityException if the BLUETOOTH_CONNECT permission is missing
     *
     * @sample
     * val result = ledControlManager.sendLedColor("00:11:22:33:44:55", 255, 100, 50, 10)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendLedColor(
        address: String,
        red: Byte,
        green: Byte,
        blue: Byte,
        transition: Byte = 0
    ): GattOperationResult {
        val data = byteArrayOf(red, green, blue, transition)
        return gattManager.sendData(
            address,
            UuidConstants.LED_CONTROL_SERVICE,
            UuidConstants.LED_COLOR_CONTROL_CHAR,
            data,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            operation = "sendLedColor"
        )
    }

    /**
     * Sends an LED color command using a [LedColor] structure.
     *
     * @param address MAC address of the target BLE device
     * @param color RGB structure with red, green, blue values
     * @param transition Optional transition speed (0–255), default = 0
     * @return [GattOperationResult] indicating success or failure
     *
     * @throws SecurityException if the BLUETOOTH_CONNECT permission is missing
     *
     * @sample
     * val result = ledControlManager.sendLedColor("00:11:22:33:44:55", LedColor.BLUE, 5)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendLedColor(
        address: String,
        color: LedColor,
        transition: Byte = 0
    ): GattOperationResult {
        return sendLedColor(address, color.red.toByte(), color.green.toByte(), color.blue.toByte(), transition)
    }

    /**
     * Sends a structured LED effect payload using [LSEffectPayload].
     *
     * @param address MAC address of the BLE device
     * @param payload LED effect structure including color, mask, period, etc.
     * @return [GattOperationResult] indicating the result of the operation
     *
     * @throws SecurityException if the BLUETOOTH_CONNECT permission is not granted
     *
     * @sample
     * val payload = LSEffectPayload(color = LedColor.RED, ledMask = 0xFFFFu)
     * val result = ledControlManager.sendLedEffect("00:11:22:33:44:55", payload)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendLedEffect(
        address: String,
        payload: LSEffectPayload
    ): GattOperationResult {
        return sendLedEffect(address, payload.toByteArray())
    }

    /**
     * Sends raw byte array representing the LED effect payload.
     *
     * @param address MAC address of the BLE device
     * @param data Byte array containing 18-byte structured effect format
     * @return [GattOperationResult] result of the transmission
     *
     * @throws SecurityException if BLUETOOTH_CONNECT permission is not granted
     *
     * @sample
     * val data = ByteArray(18) { 0x00 } // your effect bytes
     * val result = ledControlManager.sendLedEffect("00:11:22:33:44:55", data)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendLedEffect(
        address: String,
        data: ByteArray
    ): GattOperationResult {
        return gattManager.sendData(
            address,
            UuidConstants.LED_CONTROL_SERVICE,
            UuidConstants.LED_EFFECT_PAYLOAD_CHAR,
            data,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            operation = "sendLedEffect"
        )
    }

    /**
     * Called when a characteristic write completes.
     *
     * Emits the result to [ledColorWriteFlow] or [ledEffectWriteFlow]
     * depending on which characteristic was written.
     *
     * @param gatt The BluetoothGatt instance
     * @param characteristic The characteristic that was written
     * @param status Write status code (0 = success)
     */
    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        val address = gatt.device.address
        val success = status == BluetoothGatt.GATT_SUCCESS

        when (characteristic.uuid) {
            UuidConstants.LED_COLOR_CONTROL_CHAR -> ledColorWriteFlow.tryEmit(address to success)
            UuidConstants.LED_EFFECT_PAYLOAD_CHAR -> ledEffectWriteFlow.tryEmit(address to success)
        }
    }
}
