package com.lightstick.internal.ble

import android.Manifest
import androidx.annotation.RequiresPermission
import com.lightstick.internal.ble.state.InternalDeviceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Reads standard Device Information (DIS), Battery (BAS), and custom MAC info via GATT.
 * All operations are serialized by [GattClient].
 *
 * This manager is bound to a specific GattClient (and thus a specific device).
 * All methods no longer require an address parameter since the GattClient
 * is already connected to a specific device.
 */
internal class DeviceInfoManager(
    private val gattClient: GattClient
) {

    // ============================================================================================
    // BAS (Battery Service)
    // ============================================================================================

    /**
     * Battery Level (0..100).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readBatteryLevel(): Result<Int> =
        readBytes(UuidConstants.BAS_SERVICE, UuidConstants.BAS_LEVEL).mapCatching { bytes ->
            if (bytes.isEmpty()) error("Empty battery value")
            (bytes[0].toInt() and 0xFF).coerceIn(0, 100)
        }

    // ============================================================================================
    // DIS (Device Information Service)
    // ============================================================================================

    /**
     * Device Name (2A00).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readDeviceName(): Result<String> =
        readUtf8String(UuidConstants.DIS_SERVICE, UuidConstants.DIS_DEVICE_NAME)

    /**
     * Model Number (2A24).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readModelNumber(): Result<String> =
        readUtf8String(UuidConstants.DIS_SERVICE, UuidConstants.DIS_MODEL_NUMBER)

    /**
     * Firmware Revision (2A26).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readFirmwareRevision(): Result<String> =
        readUtf8String(UuidConstants.DIS_SERVICE, UuidConstants.DIS_FW_REVISION)

    /**
     * Manufacturer Name (2A29).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readManufacturerName(): Result<String> =
        readUtf8String(UuidConstants.DIS_SERVICE, UuidConstants.DIS_MANUFACTURER)

    /**
     * Read all DIS items (partial success allowed).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun readDeviceInfoAll(): DisInfo {
        val name  = readDeviceName().getOrNull()
        val model = readModelNumber().getOrNull()
        val fw    = readFirmwareRevision().getOrNull()
        val mfr   = readManufacturerName().getOrNull()

        return DisInfo(name, model, fw, mfr)
    }

    // ============================================================================================
    // MAC (Custom)
    // ============================================================================================

    /**
     * Reads custom MAC Address characteristic.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readMacAddress(): Result<String> =
        readBytes(UuidConstants.MAC_SERVICE, UuidConstants.MAC_CHAR).mapCatching { bytes ->
            // Try UTF-8 string first
            runCatching {
                val s = String(bytes, Charsets.UTF_8).trim()
                if (s.contains(":") && s.length >= 17) return@mapCatching s.uppercase()
            }
            // Fallback: 6-byte raw
            require(bytes.size >= 6) { "Invalid MAC payload length: ${bytes.size}" }
            bytes.take(6).joinToString(":") { "%02X".format(it) }
        }

    // ============================================================================================
    // Combined Snapshot
    // ============================================================================================

    /**
     * Reads all available device information and returns as InternalDeviceInfo.
     *
     * The MAC address is obtained from GattClient's getCurrentAddress().
     * If a custom MAC characteristic is available, it takes precedence.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readAllInfo(): InternalDeviceInfo {
        val address = gattClient.getCurrentAddress()
            ?: throw IllegalStateException("Not connected")

        val dis = readDeviceInfoAll()
        val bat = readBatteryLevel().getOrNull()
        val mac = readMacAddress().getOrNull()

        return InternalDeviceInfo(
            deviceName = dis.deviceName,
            modelNumber = dis.modelNumber,
            firmwareRevision = dis.firmwareRevision,
            manufacturer = dis.manufacturer,
            batteryLevel = bat,
            macAddress = mac ?: address,  // Custom MAC이 없으면 연결 주소 사용
            isConnected = true,
            rssi = null,  // RSSI는 GATT read 중에는 사용 불가
            lastUpdated = System.currentTimeMillis()
        )
    }

    // ============================================================================================
    // Helpers
    // ============================================================================================

    /**
     * Reads a characteristic and decodes as UTF-8 string.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun readUtf8String(
        service: UUID,
        characteristic: UUID
    ): Result<String> =
        readBytes(service, characteristic).mapCatching {
            String(it, Charsets.UTF_8).trim()
        }

    /**
     * Reads a characteristic as raw bytes.
     *
     * This method uses suspendCancellableCoroutine to convert the callback-based
     * GattClient API to a suspend function.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun readBytes(svc: UUID, chr: UUID): Result<ByteArray> =
        suspendCancellableCoroutine { cont ->
            gattClient.readCharacteristic(svc, chr) { res ->
                if (cont.isActive) cont.resume(res)
            }
        }

    // ============================================================================================
    // Models
    // ============================================================================================

    /**
     * Internal data class for DIS information.
     */
    private data class DisInfo(
        val deviceName: String? = null,
        val modelNumber: String? = null,
        val firmwareRevision: String? = null,
        val manufacturer: String? = null
    )
}