package io.lightstick.sdk.ble.manager

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import androidx.annotation.RequiresPermission
import io.lightstick.sdk.ble.delegate.GattEventDelegate
import io.lightstick.sdk.ble.model.DeviceInfo
import io.lightstick.sdk.ble.model.GattOperationResult
import io.lightstick.sdk.ble.model.GattOperationResult.Reason.*
import io.lightstick.sdk.ble.util.UuidConstants
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.*

/**
 * Manager responsible for reading device information and battery level over BLE.
 *
 * It handles standard GATT services and characteristics, including:
 * - Device Information Service (DIS)
 * - Battery Service
 *
 * The results of reads are emitted via [deviceInfoFlow], [batteryLevelFlow], and [deviceInfoReadStatusFlow].
 *
 * @property gattManager Reference to the [BleGattManager] for GATT communication.
 */
class DeviceInfoManager(
    private val gattManager: BleGattManager
) : GattEventDelegate {

    init {
        gattManager.addDelegate(this)
    }

    private val _deviceInfoFlow = MutableSharedFlow<Pair<String, DeviceInfo>>(replay = 1)
    /**
     * Emits complete [DeviceInfo] once all required fields are read from the device.
     *
     * The emitted value is a [Pair] of:
     * - `String`: The MAC address of the BLE device
     * - `DeviceInfo`: Contains manufacturer, model, firmware version, and device name
     *
     * This flow only emits after **all four fields** are successfully retrieved.
     *
     * @sample
     * viewModelScope.launch {
     *     deviceInfoManager.deviceInfoFlow.collect { (address, info) ->
     *         Log.d("BLE", "Device info for $address:")
     *         Log.d("BLE", "• Manufacturer: ${info.manufacturer}")
     *         Log.d("BLE", "• Model       : ${info.model}")
     *         Log.d("BLE", "• Firmware    : ${info.firmwareVersion}")
     *         Log.d("BLE", "• Name        : ${info.deviceName}")
     *     }
     * }
     */
    val deviceInfoFlow: SharedFlow<Pair<String, DeviceInfo>> = _deviceInfoFlow

    private val _batteryLevelFlow = MutableSharedFlow<Pair<String, Int>>(replay = 1)
    /**
     * Emits battery level values as percentages (0–100) reported by the device.
     *
     * The emitted value is a [Pair] of:
     * - `String`: The MAC address of the BLE device
     * - `Int`: Battery level percentage (0–100)
     *
     * @sample
     * viewModelScope.launch {
     *     deviceInfoManager.batteryLevelFlow.collect { (address, level) ->
     *         Log.d("BLE", "Battery for $address = $level%")
     *     }
     * }
     */
    val batteryLevelFlow: SharedFlow<Pair<String, Int>> = _batteryLevelFlow

    private val _deviceInfoReadStatusFlow = MutableSharedFlow<GattOperationResult>(replay = 1)
    /**
     * Emits [GattOperationResult] to indicate the result of a device info read attempt.
     *
     * This flow helps determine whether the read succeeded or failed.
     * Useful for displaying error messages or retrying on failure.
     *
     * The result includes:
     * - `address`: BLE MAC address
     * - `operation`: e.g., "readDeviceInfo"
     * - `reason`: failure reason if applicable
     *
     * @sample
     * viewModelScope.launch {
     *     deviceInfoManager.deviceInfoReadStatusFlow.collect { result ->
     *         when (result) {
     *             is GattOperationResult.Success ->
     *                 Log.d("BLE", "Info read success: ${result.address}")
     *             is GattOperationResult.Failure ->
     *                 Log.e("BLE", "Info read failed: ${result.reason}")
     *         }
     *     }
     * }
     */
    val deviceInfoReadStatusFlow: SharedFlow<GattOperationResult> = _deviceInfoReadStatusFlow

    // Cache to store partial DeviceInfo during async read
    private val deviceInfoCache = mutableMapOf<String, MutableMap<UUID, String>>()


    /**
     * Reads all characteristics defined under Device Information Service.
     * Emits [DeviceInfo] via [deviceInfoFlow] after all fields are collected.
     *
     * @param address MAC address of the BLE device.
     * @return [GattOperationResult] indicating success or failure.
     * @throws SecurityException if `BLUETOOTH_CONNECT` permission is not granted.
     *
     * @sample
     * val result = deviceInfoManager.readDeviceInfo("00:11:22:33:44:55")
     * if (result is GattOperationResult.Success) {
     *     // Wait for result via deviceInfoFlow
     * }
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readDeviceInfo(address: String): GattOperationResult {
        val uuids = listOf(
            UuidConstants.MANUFACTURER_NAME_CHAR,
            UuidConstants.MODEL_NUMBER_CHAR,
            UuidConstants.FW_REVISION_CHAR,
            UuidConstants.DEVICE_NAME_CHAR
        )

        val serviceUUID = UuidConstants.DEVICE_INFO_SERVICE

        for (uuid in uuids) {
            val result = gattManager.readData(address, serviceUUID, uuid, "readDeviceInfo")
            if (result is GattOperationResult.Failure) {
                _deviceInfoReadStatusFlow.emit(result)
                return result
            }

            kotlinx.coroutines.delay(100)
        }

        return GattOperationResult.Success(address, "readDeviceInfo")
    }


    /**
     * Reads battery level characteristic (Battery Service).
     * Result is emitted via [batteryLevelFlow].
     *
     * @param address MAC address of the BLE device.
     * @return [GattOperationResult] indicating read success/failure.
     *
     * @sample
     * val result = deviceInfoManager.readBatteryLevel("00:11:22:33:44:55")
     * if (result is GattOperationResult.Success) {
     *     // Wait for battery value from batteryLevelFlow
     * }
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readBatteryLevel(address: String): GattOperationResult {
        return gattManager.readData(
            address,
            UuidConstants.BATTERY_SERVICE,
            UuidConstants.BATTERY_LEVEL_CHAR,
            "readBatteryLevel"
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun enableBatteryNotification(address: String): GattOperationResult {
        return gattManager.enableNotification(
            address = address,
            serviceUUID = UuidConstants.BATTERY_SERVICE,
            characteristicUUID = UuidConstants.BATTERY_LEVEL_CHAR,
            operation = "enableBatteryNotification"
        )
    }

    /**
     * Callback invoked when any characteristic read completes.
     *
     * Automatically routes and parses the following:
     * - Device Info fields into [deviceInfoFlow]
     * - Battery level into [batteryLevelFlow]
     *
     * @param gatt The BluetoothGatt instance
     * @param characteristic The characteristic that was read
     * @param status The result of the read operation
     */
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) return

        val address = gatt.device.address
        val uuid = characteristic.uuid

        when (uuid) {
            UuidConstants.BATTERY_LEVEL_CHAR -> {
                @Suppress("DEPRECATION")
                val batteryLevel = characteristic.value?.firstOrNull()?.toInt()
                if (batteryLevel != null) {
                    _batteryLevelFlow.tryEmit(address to batteryLevel)
                }
            }

            in setOf(
                UuidConstants.MANUFACTURER_NAME_CHAR,
                UuidConstants.MODEL_NUMBER_CHAR,
                UuidConstants.FW_REVISION_CHAR,
                UuidConstants.DEVICE_NAME_CHAR
            ) -> {
                @Suppress("DEPRECATION")
                val value = characteristic.value?.toString(Charsets.UTF_8) ?: return

                val map = deviceInfoCache.getOrPut(address) { mutableMapOf() }
                map[uuid] = value

                val info = DeviceInfo(
                    manufacturer = map[UuidConstants.MANUFACTURER_NAME_CHAR],
                    model = map[UuidConstants.MODEL_NUMBER_CHAR],
                    firmwareVersion = map[UuidConstants.FW_REVISION_CHAR],
                    deviceName = map[UuidConstants.DEVICE_NAME_CHAR]
                )

                if (info.manufacturer != null &&
                    info.model != null &&
                    info.firmwareVersion != null &&
                    info.deviceName != null
                ) {
                    _deviceInfoFlow.tryEmit(address to info)
                    deviceInfoCache.remove(address)
                }
            }
        }
    }

    /**
     * Callback invoked when a characteristic notification is received.
     *
     * Automatically routes and parses the following notifications:
     * - Battery Level (from BATTERY_LEVEL_CHAR) into [batteryLevelFlow]
     *
     * Extend this method to handle other characteristic notifications if needed.
     *
     * @param gatt The BluetoothGatt instance associated with the callback
     * @param characteristic The characteristic that triggered the notification
     */
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val address = gatt.device.address
        val uuid = characteristic.uuid

        if (uuid == UuidConstants.BATTERY_LEVEL_CHAR) {
            @Suppress("DEPRECATION")
            val batteryLevel = characteristic.value?.firstOrNull()?.toInt()
            if (batteryLevel != null) {
                _batteryLevelFlow.tryEmit(address to batteryLevel)
            }
        }
    }
}
