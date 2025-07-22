package io.lightstick.sdk.ble.manager

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import androidx.annotation.RequiresPermission
import io.lightstick.sdk.ble.delegate.GattEventDelegate
import io.lightstick.sdk.ble.model.GattOperationResult
import io.lightstick.sdk.ble.model.GattOperationResult.Reason.*
import io.lightstick.sdk.ble.model.OtaState
import io.lightstick.sdk.ble.ota.OtaOpcode
import io.lightstick.sdk.ble.ota.TelinkOtaPacketParser
import io.lightstick.sdk.ble.util.CrcUtil
import io.lightstick.sdk.ble.util.UuidConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Handles Telink-compatible OTA (Over-the-Air) firmware updates over BLE.
 *
 * This manager provides a full OTA flow including notification setup,
 * initialization commands, firmware chunk writing, and completion.
 *
 * @property gattManager Reference to BleGattManager for GATT operations
 */
class OtaManager(
    private val gattManager: BleGattManager
) : GattEventDelegate {

    // Internal state flow for OTA progress tracking
    private val _otaProgressFlow = MutableStateFlow<Pair<String, Int>?>(null)

    /**
     * Emits firmware update progress for each BLE device as a pair of:
     * - `address`: MAC address of the device being updated
     * - `percent`: Integer progress value (0 to 100)
     *
     * Collect this flow to display real-time OTA progress in the UI or log.
     * Emitted periodically while OTA is in progress.
     *
     * @see sendOtaFirmware
     *
     * @sample
     * viewModelScope.launch {
     *     otaManager.otaProgressFlow.collect { progress ->
     *         progress?.let { (address, percent) ->
     *             Log.d("OTA", "OTA progress for $address = $percent%")
     *         }
     *     }
     * }
     */
    val otaProgressFlow: StateFlow<Pair<String, Int>?> = _otaProgressFlow

    // Internal state flow for OTA status tracking
    private val _otaStateFlow = MutableStateFlow<Pair<String, OtaState>?>(null)

    /**
     * Emits the current OTA state for each BLE device as a pair of:
     * - `address`: MAC address of the device
     * - `state`: [OtaState] which can be one of:
     *     - [OtaState.InProgress]: OTA transfer is ongoing
     *     - [OtaState.Completed]: OTA transfer successfully completed
     *     - [OtaState.Failed]: OTA transfer failed or aborted
     *
     * Use this flow to observe OTA success/failure and update UI state accordingly.
     *
     * @see sendOtaFirmware
     *
     * @sample
     * viewModelScope.launch {
     *     otaManager.otaStateFlow.collect { state ->
     *         state?.let { (address, status) ->
     *             when (status) {
     *                 OtaState.InProgress -> Log.d("OTA", "$address is updating...")
     *                 OtaState.Completed -> Log.d("OTA", "$address update completed!")
     *                 OtaState.Failed -> Log.e("OTA", "$address update failed.")
     *             }
     *         }
     *     }
     * }
     */
    val otaStateFlow: StateFlow<Pair<String, OtaState>?> = _otaStateFlow

    /**
     * Registers this OTA manager as a delegate for receiving GATT events
     * such as characteristic write or notification.
     *
     * This is required to track OTA result notifications from the device.
     */
    init {
        gattManager.addDelegate(this)
    }


    /**
     * Performs full OTA firmware update flow for Telink BLE device.
     *
     * Steps:
     * 1. Enable OTA notification (CCCD)
     * 2. Send OTA init command (FF00)
     * 3. Send OTA start command (FF01)
     * 4. Transmit firmware packets with CRC
     * 5. Send OTA end command (FF02)
     *
     * @param address MAC address of the BLE device
     * @param firmware Firmware binary to be sent
     * @param delayMs Delay between packet transmissions (default 10ms)
     * @return GattOperationResult indicating success or failure of the OTA process
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted
     *
     * @sample
     * ```
     * val data = File("firmware.bin").readBytes()
     * val result = otaManager.sendOtaFirmware("00:11:22:33:44:55", data)
     * if (result is GattOperationResult.Failure) {
     *     Log.e("OTA", "OTA failed: ${result.reason}")
     * }
     * ```
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun sendOtaFirmware(
        address: String,
        firmware: ByteArray,
        delayMs: Long = 10L
    ): GattOperationResult {
        val mtuPayload = gattManager.getMtuPayloadSize(address) ?: 20
        val parser = TelinkOtaPacketParser().apply {
            set(firmware, mtuPayload)
        }

        enableOtaNotification(address).let {
            if (it is GattOperationResult.Failure) return it
        }

        delay(200)

        sendOtaInitCommand(address).let {
            if (it is GattOperationResult.Failure) return it
        }

        _otaStateFlow.tryEmit(address to OtaState.InProgress)

        sendOtaStart(address).let {
            if (it is GattOperationResult.Failure) return it
        }

        while (parser.hasNext()) {
            val packet = parser.getNextPacket()
            sendOtaChunk(address, packet).let {
                if (it is GattOperationResult.Failure) {
                    _otaStateFlow.tryEmit(address to OtaState.Failed)
                    return it
                }
            }

            _otaProgressFlow.tryEmit(address to parser.getProgress())

            if (_otaStateFlow.value?.second == OtaState.Failed) {
                return GattOperationResult.Failure(address, "sendOtaFirmware", OtaAborted)
            }

            delay(delayMs)
        }

        return sendOtaEnd(address, firmware.size, CrcUtil.crc16(firmware))
    }

    /**
     * Sends OTA Init command (FF00) to BLE device.
     *
     * @param address MAC address of the BLE device
     * @return GattOperationResult indicating success or failure
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted
     *
     * @sample
     * ```
     * val result = otaManager.sendOtaInitCommand("00:11:22:33:44:55")
     * ```
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendOtaInitCommand(address: String): GattOperationResult {
        val data = OtaOpcode.OTA_VERSION.toBytes()
        return gattManager.sendData(
            address,
            UuidConstants.FIRMWARE_UPDATE_SERVICE,
            UuidConstants.OTA_CHAR,
            data,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            operation = "sendOtaInit"
        )
    }

    /**
     * Sends OTA Start command (FF01) to begin firmware transmission.
     *
     * @param address MAC address of the BLE device
     * @return GattOperationResult indicating success or failure
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendOtaStart(address: String): GattOperationResult {
        val data = OtaOpcode.START_LEGACY.toBytes()
        return gattManager.sendData(
            address,
            UuidConstants.FIRMWARE_UPDATE_SERVICE,
            UuidConstants.OTA_CHAR,
            data,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            operation = "sendOtaStart"
        )
    }

    /**
     * Sends a single OTA firmware packet (index + data + CRC).
     *
     * @param address MAC address of the BLE device
     * @param packet 20-byte OTA packet
     * @return GattOperationResult indicating success or failure
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendOtaChunk(address: String, packet: ByteArray): GattOperationResult {
        return gattManager.sendData(
            address,
            UuidConstants.FIRMWARE_UPDATE_SERVICE,
            UuidConstants.OTA_CHAR,
            packet,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            operation = "sendOtaChunk"
        )
    }


    /**
     * Sends OTA End command (FF02) with firmware block count and CRC.
     *
     * @param address MAC address of the BLE device
     * @param firmwareSize Size of the firmware in bytes
     * @param crc CRC-16 checksum of the entire firmware
     * @return GattOperationResult indicating success or failure
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendOtaEnd(address: String, firmwareSize: Int, crc: Int): GattOperationResult {
        val totalBlocks = (firmwareSize + 15) / 16
        val packet = OtaOpcode.END.toBytes() +
                byteArrayOf(
                    (totalBlocks and 0xFF).toByte(),
                    ((totalBlocks shr 8) and 0xFF).toByte(),
                    (crc and 0xFF).toByte(),
                    ((crc shr 8) and 0xFF).toByte()
                )
        return gattManager.sendData(
            address,
            UuidConstants.FIRMWARE_UPDATE_SERVICE,
            UuidConstants.OTA_CHAR,
            packet,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            operation = "sendOtaEnd"
        )
    }

    /**
     * Enables OTA notification by writing to the CCCD descriptor.
     *
     * This method sets local notification via `setCharacteristicNotification()` and then writes
     * the standard CCCD descriptor to enable notifications from the BLE device.
     *
     * @param address MAC address of the BLE device
     * @return GattOperationResult indicating immediate success or failure
     * @throws SecurityException If BLUETOOTH_CONNECT permission is not granted
     *
     * @sample
     * ```
     * val result = otaManager.enableOtaNotification("00:11:22:33:44:55")
     * ```
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun enableOtaNotification(address: String): GattOperationResult {
        return gattManager.enableNotification(
            address = address,
            serviceUUID = UuidConstants.FIRMWARE_UPDATE_SERVICE,
            characteristicUUID = UuidConstants.OTA_CHAR,
            operation = "enableOtaNotification"
        )
    }


    /**
     * Handles OTA result notification sent by the BLE device.
     *
     * Expected payload: [0x06, 0xFF, resultCode]
     * Where resultCode == 0 means success, else failure.
     *
     * @param gatt BluetoothGatt instance
     * @param characteristic Notified characteristic containing result
     */
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        @Suppress("DEPRECATION")
        val value = characteristic.value ?: return

        if (value.size >= 3 && value[0] == 0x06.toByte() && value[1] == 0xFF.toByte()) {
            val resultCode = value[2].toInt() and 0xFF
            val state = if (resultCode == 0) OtaState.Completed else OtaState.Failed
            _otaStateFlow.tryEmit(gatt.device.address to state)
        }
    }

    /**
     * Handles write failure during OTA, marking the OTA state as Failed.
     *
     * @param gatt BluetoothGatt instance
     * @param characteristic Characteristic that was written to
     * @param status Result of the write operation
     */
    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            _otaStateFlow.tryEmit(gatt.device.address to OtaState.Failed)
        }
    }
}
