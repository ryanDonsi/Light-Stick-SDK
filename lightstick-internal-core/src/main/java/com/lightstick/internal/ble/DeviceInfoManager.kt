package com.lightstick.internal.ble

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import com.lightstick.internal.ble.state.InternalDeviceInfo
import kotlinx.coroutines.delay
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
     * Returns true if the connected device exposes the BAS Battery Level characteristic (0x2A19).
     * Safe to call from any thread after service discovery; returns false if not connected.
     */
    fun isBatterySupported(): Boolean =
        gattClient.hasCharacteristic(UuidConstants.BAS_SERVICE, UuidConstants.BAS_LEVEL)

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
     * Device Name (0x2A00 under GAP 0x1800, not DIS 0x180A).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readDeviceName(): Result<String> =
        readUtf8String(UuidConstants.GAP_SERVICE, UuidConstants.DIS_DEVICE_NAME)

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
     *
     * Waits briefly for the device to stabilize after connection, then retries
     * each characteristic up to [READ_RETRY_ATTEMPTS] times before accepting null.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun readDeviceInfoAll(): DisInfo {
        delay(POST_CONNECT_DELAY_MS)

        val name  = readWithRetry("deviceName")  { readDeviceName() }
        val model = readWithRetry("modelNumber")  { readModelNumber() }
        val fw    = readWithRetry("fwRevision")   { readFirmwareRevision() }
        val mfr   = readWithRetry("manufacturer") { readManufacturerName() }

        Log.d(TAG, "DIS read complete — name=$name model=$model fw=$fw mfr=$mfr")
        return DisInfo(name, model, fw, mfr)
    }

    /**
     * Retries [read] up to [READ_RETRY_ATTEMPTS] times, returning the first non-blank
     * success value. Returns null only if all attempts fail or return a blank string.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun readWithRetry(label: String, read: suspend () -> Result<String>): String? {
        repeat(READ_RETRY_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(READ_RETRY_DELAY_MS)
            val result = read()
            val value = result.getOrNull()?.takeUnless { it.isBlank() }
            if (value != null) return value
            Log.w(TAG, "[$label] attempt ${attempt + 1}/$READ_RETRY_ATTEMPTS failed: ${result.exceptionOrNull()?.message ?: "blank"}")
        }
        return null
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

    private data class DisInfo(
        val deviceName: String? = null,
        val modelNumber: String? = null,
        val firmwareRevision: String? = null,
        val manufacturer: String? = null
    )

    companion object {
        private const val TAG = "DeviceInfoManager"
        /** Delay after connection before starting DIS reads (device stabilization). */
        private const val POST_CONNECT_DELAY_MS = 300L
        /** Maximum read attempts per characteristic. */
        private const val READ_RETRY_ATTEMPTS = 3
        /** Delay between retry attempts. */
        private const val READ_RETRY_DELAY_MS = 400L
    }
}