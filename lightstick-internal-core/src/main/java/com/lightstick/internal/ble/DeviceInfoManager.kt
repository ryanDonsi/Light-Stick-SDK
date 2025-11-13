package com.lightstick.internal.ble

import android.Manifest
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Reads standard Device Information (DIS), Battery (BAS), and custom MAC info via GATT.
 * All operations are serialized by [GattClient].
 */
internal class DeviceInfoManager(
    private val gattClient: GattClient
) {

    // ===== BAS (Battery Service) =============================================
    /** Battery Level (0..100). */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readBatteryLevel(address: String): Result<Int> =
        readBytes(address, UuidConstants.BAS_SERVICE, UuidConstants.BAS_LEVEL).mapCatching { bytes ->
            if (bytes.isEmpty()) error("Empty battery value")
            (bytes[0].toInt() and 0xFF).coerceIn(0, 100)
        }

    // ===== DIS (Device Information Service) ==================================
    /** Device Name (2A00). */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readDeviceName(address: String): Result<String> =
        readUtf8String(address, UuidConstants.DIS_SERVICE, UuidConstants.DIS_DEVICE_NAME)

    /** Model Number (2A24). */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readModelNumber(address: String): Result<String> =
        readUtf8String(address, UuidConstants.DIS_SERVICE, UuidConstants.DIS_MODEL_NUMBER)

    /** Firmware Revision (2A26). */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readFirmwareRevision(address: String): Result<String> =
        readUtf8String(address, UuidConstants.DIS_SERVICE, UuidConstants.DIS_FW_REVISION)

    /** Manufacturer Name (2A29). */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readManufacturerName(address: String): Result<String> =
        readUtf8String(address, UuidConstants.DIS_SERVICE, UuidConstants.DIS_MANUFACTURER)

    /** Read all DIS items (partial success allowed). */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readDeviceInfoAll(address: String): Result<DisInfo> {
        val name  = readDeviceName(address).getOrNull()
        val model = readModelNumber(address).getOrNull()
        val fw    = readFirmwareRevision(address).getOrNull()
        val mfr   = readManufacturerName(address).getOrNull()

        return if (name == null && model == null && fw == null && mfr == null) {
            Result.failure(IllegalStateException("All DIS reads failed"))
        } else {
            Result.success(DisInfo(name, model, fw, mfr))
        }
    }

    // ===== MAC (Custom) ======================================================
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readMacAddress(address: String): Result<String> =
        readBytes(address, UuidConstants.MAC_SERVICE, UuidConstants.MAC_CHAR).mapCatching { bytes ->
            // Try UTF-8 string first
            runCatching {
                val s = String(bytes, Charsets.UTF_8).trim()
                if (s.contains(":") && s.length >= 17) return@mapCatching s.uppercase()
            }
            // Fallback: 6-byte raw
            require(bytes.size >= 6) { "Invalid MAC payload length: ${bytes.size}" }
            bytes.take(6).joinToString(":") { "%02X".format(it) }
        }

    // ===== Combined Snapshot =================================================
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readAllSnapshot(address: String): Result<DeviceSnapshot> {
        val dis = readDeviceInfoAll(address).getOrNull()
        val bat = readBatteryLevel(address).getOrNull()
        val mac = readMacAddress(address).getOrNull()

        return if (dis == null && bat == null && mac == null) {
            Result.failure(IllegalStateException("All info reads failed"))
        } else {
            Result.success(
                DeviceSnapshot(
                    deviceName = dis?.deviceName,
                    modelNumber = dis?.modelNumber,
                    firmwareRevision = dis?.firmwareRevision,
                    manufacturer = dis?.manufacturer,
                    batteryLevel = bat,
                    macAddress = mac
                )
            )
        }
    }

    // ===== Helpers ===========================================================
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun readUtf8String(
        address: String,
        service: UUID,
        characteristic: UUID
    ): Result<String> =
        readBytes(address, service, characteristic).mapCatching { String(it, Charsets.UTF_8).trim() }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun readBytes(address: String, svc: UUID, chr: UUID): Result<ByteArray> =
        suspendCancellableCoroutine { cont ->
            gattClient.readCharacteristic(address, svc, chr) { res ->
                if (cont.isActive) cont.resume(res)
            }
        }

    // ===== Models ============================================================
    internal data class DisInfo(
        val deviceName: String? = null,
        val modelNumber: String? = null,
        val firmwareRevision: String? = null,
        val manufacturer: String? = null
    )

    internal data class DeviceSnapshot(
        val deviceName: String? = null,
        val modelNumber: String? = null,
        val firmwareRevision: String? = null,
        val manufacturer: String? = null,
        val batteryLevel: Int? = null,
        val macAddress: String? = null
    )
}
