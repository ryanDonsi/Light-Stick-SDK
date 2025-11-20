@file:Suppress("MissingPermission", "unused")

package com.lightstick

import android.Manifest
import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import com.lightstick.device.Device
import com.lightstick.device.Controller
import com.lightstick.internal.api.Facade
import com.lightstick.types.Color
import com.lightstick.types.LSEffectPayload

/**
 * Public BLE façade for scanning, connecting, global queries, and simple broadcasts.
 *
 * Design:
 * - **Per-device control**: Use [Device.connect] and the provided [Controller] to call
 *   `sendColor`, `sendEffect`, `requestMtu`, `readBattery`, etc.
 * - **Global control/queries**: Use this singleton for scanning, shutdown, listing connected/bonded
 *   devices, and broadcasting the same color/effect to all connected devices.
 *
 * Permissions:
 * - Scan: [Manifest.permission.BLUETOOTH_SCAN] on API 31+, or location permissions on API ≤ 30.
 * - Connect/Read/Write: [Manifest.permission.BLUETOOTH_CONNECT] on API 31+.
 *
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.BleSamples.sampleScanAndConnect
 */
object LSBluetooth {

    // --------------------------------------------------------------------------------------------
    // Init / Scan
    // --------------------------------------------------------------------------------------------

    /**
     * Initializes the internal BLE façade. Idempotent.
     *
     * Call this once at app startup (e.g., in `Application.onCreate`).
     *
     * @param context Application or activity context; the application context will be retained.
     * @throws IllegalStateException If initialization fails unexpectedly.
     * @sample com.lightstick.samples.BleSamples.sampleInitialize
     */
    @JvmStatic
    @MainThread
    fun initialize(context: Context) {
        Facade.initialize(context)
    }

    /**
     * Starts BLE scanning and reports discovered devices via [onDeviceFound].
     *
     * The provided callback may be invoked multiple times as scan results update.
     *
     * @param onDeviceFound Callback receiving a [Device] for each scan result.
     * @throws SecurityException If required scan permissions are not granted.
     * @sample com.lightstick.samples.BleSamples.sampleStartScan
     */
    @JvmStatic
    @MainThread
    @RequiresPermission(
        anyOf = [
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ]
    )
    fun startScan(onDeviceFound: (Device) -> Unit) {
        Facade.startScan { mac, name, rssi ->
            onDeviceFound(Device(mac = mac, name = name, rssi = rssi))
        }
    }

    /**
     * Stops an ongoing BLE scan.
     *
     * @throws SecurityException If scan permissions are missing on the current API level.
     * @sample com.lightstick.samples.BleSamples.sampleStopScan
     */
    @JvmStatic
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        Facade.stopScan()
    }

    // --------------------------------------------------------------------------------------------
    // Global queries (connected/bonded lists & counts)
    // --------------------------------------------------------------------------------------------

    /**
     * Returns the list of **currently connected** devices.
     *
     * @return A list of [Device] objects (name/RSSI may be null or last-known).
     * @throws SecurityException If Bluetooth connect permission is not granted.
     * @sample com.lightstick.samples.BleSamples.sampleConnectedDevices
     */
    @JvmStatic
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectedDevices(): List<Device> =
        Facade.connectedList().map { (mac, name, rssi) -> Device(mac, name, rssi) }

    /**
     * Returns the number of **currently connected** devices.
     *
     * @return Connected device count.
     * @throws SecurityException If Bluetooth connect permission is not granted.
     * @sample com.lightstick.samples.BleSamples.sampleConnectedCount
     */
    @JvmStatic
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectedCount(): Int = Facade.connectedCount()

    /**
     * Returns the system-bonded (paired) device list. RSSI is not available and thus `null`.
     *
     * @return A list of bonded [Device] entries (name may be null).
     * @throws SecurityException If Bluetooth connect permission is not granted.
     * @sample com.lightstick.samples.BleSamples.sampleBondedDevices
     */
    @JvmStatic
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun bondedDevices(): List<Device> =
        Facade.bondedList().map { (mac, name) -> Device(mac, name, /* rssi = */ null) }

    /**
     * Returns the number of system-bonded (paired) devices.
     *
     * @return Bonded device count.
     * @throws SecurityException If Bluetooth connect permission is not granted.
     * @sample com.lightstick.samples.BleSamples.sampleBondedCount
     */
    @JvmStatic
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun bondedCount(): Int = Facade.bondedCount()

    // --------------------------------------------------------------------------------------------
    // Broadcast helpers (send to all connected devices)
    // --------------------------------------------------------------------------------------------

    /**
     * Sends the same color to **all currently connected** devices.
     *
     * This is a convenience broadcast. If you need per-device timing or different transitions,
     * connect to each device and use the per-device [Controller] instead.
     *
     * Internally this uses a single broadcast call to the internal façade, rather than
     * iterating the connected list in the public layer.
     *
     * @param color The RGB color to send.
     * @param transition Transition byte (0–255) appended as the 4th byte of the color packet.
     * @throws SecurityException If Bluetooth connect permission is not granted.
     * @sample com.lightstick.samples.BleSamples.sampleBroadcastColor
     */
    @JvmStatic
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun broadcastColor(color: Color, transition: Int = 0) {
        val packet = byteArrayOf(
            color.r.toByte(), color.g.toByte(), color.b.toByte(),
            transition.coerceIn(0, 255).toByte()
        )
        // Single internal broadcast instead of per-device loop.
        Facade.sendColorPacket(packet)
    }

    /**
     * Sends the same 20-byte effect frame to **all currently connected** devices.
     *
     * Useful when broadcasting a synchronized single-frame effect.
     * For time-sequenced playback, prefer sending a full timeline from your app logic,
     * or use per-device controllers for precise control.
     *
     * @param payload The effect payload; must encode to 20 bytes.
     * @throws IllegalArgumentException If the encoded payload is not exactly 20 bytes.
     * @throws SecurityException If Bluetooth connect permission is not granted.
     * @sample com.lightstick.samples.BleSamples.sampleBroadcastEffect
     */
    @JvmStatic
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun broadcastEffect(payload: LSEffectPayload) {
        val frame = payload.toByteArray()
        require(frame.size == 20) { "Effect payload must be 20 bytes" }
        // Single internal broadcast instead of per-device loop.
        Facade.sendEffectPayload(frame)
    }

    // --------------------------------------------------------------------------------------------
    // Shutdown (release all sessions)
    // --------------------------------------------------------------------------------------------

    /**
     * Disconnects from all devices and releases internal resources.
     *
     * Call this on app shutdown or user logout.
     *
     * @throws SecurityException If Bluetooth connect permission is not granted.
     * @sample com.lightstick.samples.BleSamples.sampleShutdown
     */
    @JvmStatic
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun shutdown() {
        Facade.shutdown()
    }
}
