package com.lightstick.device

import android.Manifest
import androidx.annotation.RequiresPermission
import com.lightstick.types.Color
import com.lightstick.types.LSEffectPayload

/**
 * Per-device controller created after a successful connection.
 *
 * All operations performed through this controller are scoped to the bound [device] only.
 * BLE operations require BLUETOOTH_CONNECT permission; individual read methods return
 * results via callbacks instead of throwing exceptions.
 *
 * Implementations are provided by the SDK; application code should not implement this interface.
 *
 * Sample usage:
 * @sample com.lightstick.samples.DeviceSamples.sampleSendColor
 * @sample com.lightstick.samples.DeviceSamples.sampleSendEffect
 * @sample com.lightstick.samples.DeviceSamples.samplePlayFrames
 * @sample com.lightstick.samples.DeviceSamples.sampleRequestMtu
 * @sample com.lightstick.samples.DeviceSamples.sampleReadDeviceName
 * @sample com.lightstick.samples.DeviceSamples.sampleReadModelNumber
 * @sample com.lightstick.samples.DeviceSamples.sampleReadFirmwareRevision
 * @sample com.lightstick.samples.DeviceSamples.sampleReadManufacturer
 * @sample com.lightstick.samples.DeviceSamples.sampleReadMacAddress
 * @sample com.lightstick.samples.DeviceSamples.sampleReadBattery
 * @sample com.lightstick.samples.DeviceSamples.sampleDisconnect
 * @sample com.lightstick.samples.DeviceSamples.sampleStartOta
 * @sample com.lightstick.samples.DeviceSamples.sampleAbortOta
 */
interface Controller {

    /** The device this controller targets. */
    val device: Device

    /**
     * Sends a 4-byte color packet [R, G, B, transition] to the device.
     *
     * @param color RGB components in the SDK color model.
     * @param transition Transition time in frames or milliseconds (implementation-specific);
     *                   values are usually clamped to a valid range.
     * @throws SecurityException Implementations should guard with permission checks.
     *
     * @sample com.lightstick.samples.DeviceSamples.sampleSendColor
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendColor(color: Color, transition: Int)

    /**
     * Sends a 16-byte effect payload to the device.
     *
     * @param payload Structured effect payload (16 bytes on the wire).
     * @throws SecurityException Implementations should guard with permission checks.
     *
     * @sample com.lightstick.samples.DeviceSamples.sampleSendEffect
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendEffect(payload: LSEffectPayload)

    /**
     * Streams timestamped frames (each 16B payload with a presentation timestamp).
     *
     * @param frames List of pairs where first is timestamp (e.g., ms) and second is the 16B payload.
     * @throws SecurityException Implementations should guard with permission checks.
     *
     * @sample com.lightstick.samples.DeviceSamples.samplePlayFrames
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun play(frames: List<Pair<Long, ByteArray>>)

    /**
     * Requests an MTU negotiation with the device.
     *
     * @param preferred Preferred MTU value.
     * @param onResult Callback receiving the negotiated MTU wrapped in [Result].
     *                 On failure, [Result.failure] contains the cause.
     * @throws SecurityException Implementations should guard with permission checks.
     *
     * @sample com.lightstick.samples.DeviceSamples.sampleRequestMtu
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestMtu(preferred: Int, onResult: (Result<Int>) -> Unit)

    // ---- Device Information (item-by-item reads; do not expose internal types) ----------------

    /**
     * Reads the **Device Name (DIS 2A00)** characteristic from the connected device.
     *
     * This corresponds to the standard *Device Information Service (0x180A)* characteristic
     * that provides a human-readable name for the device.
     *
     * This method performs a GATT read operation; results are delivered asynchronously.
     *
     * @param onResult Callback invoked upon completion.
     *                 - [Result.success] contains the UTF-8 decoded device name.
     *                 - [Result.failure] contains the error encountered.
     *
     * @throws SecurityException If BLUETOOTH_CONNECT permission is missing.
     *
     * @sample com.lightstick.samples.DeviceSamples.sampleReadDeviceName
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readDeviceName(onResult: (Result<String>) -> Unit)

    /**
     * Reads the **Model Number (DIS 2A24)** characteristic from the connected device.
     *
     * This field typically identifies the hardware or product variant, such as
     * "LS-TYPE-A" or "Rev2.0".
     *
     * @param onResult Callback invoked with the result.
     *                 - [Result.success] provides the model number string.
     *                 - [Result.failure] contains the cause of error.
     *
     * @throws SecurityException If BLUETOOTH_CONNECT permission is missing.
     *
     * @sample com.lightstick.samples.DeviceSamples.sampleReadModelNumber
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readModelNumber(onResult: (Result<String>) -> Unit)

    /**
     * Reads the **Firmware Revision (DIS 2A26)** characteristic from the connected device.
     *
     * The returned string usually represents the firmware build or semantic version,
     * e.g. `"v1.3.2"` or `"2025-11-10_Release"`.
     *
     * @param onResult Callback invoked with the result.
     *                 - [Result.success] provides the firmware revision string.
     *                 - [Result.failure] contains the cause of error.
     *
     * @throws SecurityException If BLUETOOTH_CONNECT permission is missing.
     *
     * @sample com.lightstick.samples.DeviceSamples.sampleReadFirmwareRevision
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readFirmwareRevision(onResult: (Result<String>) -> Unit)

    /**
     * Reads the **Manufacturer Name (DIS 2A29)** characteristic from the connected device.
     *
     * This identifies the vendor or brand that produced the hardware, for example `"LightStick Co."`.
     *
     * @param onResult Callback invoked with the result.
     *                 - [Result.success] provides the manufacturer name string.
     *                 - [Result.failure] contains the cause of error.
     *
     * @throws SecurityException If BLUETOOTH_CONNECT permission is missing.
     *
     * @sample com.lightstick.samples.DeviceSamples.sampleReadManufacturer
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readManufacturer(onResult: (Result<String>) -> Unit)

    /**
     * Reads a custom GATT characteristic that exposes the device's internal MAC address.
     *
     * Depending on the firmware, this may be represented as:
     * - A standard MAC string (e.g., `"AA:BB:CC:DD:EE:FF"`)
     * - A raw 6-byte value encoded as hex.
     *
     * This characteristic is **not part of the standard DIS** and is provided by
     * LightStick-specific firmware.
     *
     * @param onResult Callback invoked with the result.
     *                 - [Result.success] provides the MAC address string or hex bytes.
     *                 - [Result.failure] contains the cause of error.
     *
     * @throws SecurityException If BLUETOOTH_CONNECT permission is missing.
     *
     * @sample com.lightstick.samples.DeviceSamples.sampleReadMacAddress
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readMacAddress(onResult: (Result<String>) -> Unit)

    /**
     * Reads the **Battery Level (BAS 2A19)** characteristic from the connected device.
     *
     * The value represents the current battery percentage as an integer between `0` and `100`.
     * Firmware may update this value periodically or upon specific BLE notifications.
     *
     * @param onResult Callback invoked with the result.
     *                 - [Result.success] provides the battery level (0–100).
     *                 - [Result.failure] contains the cause of error.
     *
     * @throws SecurityException If BLUETOOTH_CONNECT permission is missing.
     *
     * @sample com.lightstick.samples.DeviceSamples.sampleReadBattery
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readBattery(onResult: (Result<Int>) -> Unit)

    /**
     * Starts an OTA update on this device.
     *
     * @param firmware Raw firmware bytes to be transferred.
     * @param onProgress Progress callback (0..100).
     * @param onResult Completion callback with [Result].
     * @throws SecurityException Implementations should guard with permission checks.
     *
     * @sample com.lightstick.samples.DeviceSamples.sampleStartOta
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startOta(
        firmware: ByteArray,
        onProgress: (Int) -> Unit,
        onResult: (Result<Unit>) -> Unit
    )

    /**
     * Aborts any ongoing OTA (Over-The-Air firmware update) operation, if one is in progress.
     *
     * This method immediately cancels the active OTA session for this device.
     * The cancellation is best-effort and may not revert any partial firmware writes already sent.
     *
     * Once aborted, the device should reboot or remain in its previous firmware state.
     *
     * @throws SecurityException If BLUETOOTH_CONNECT permission is missing.
     *
     * @sample com.lightstick.samples.DeviceSamples.sampleAbortOta
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun abortOta()
}
