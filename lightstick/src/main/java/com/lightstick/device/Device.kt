package com.lightstick.device

import android.Manifest
import android.os.Parcelable
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import com.lightstick.internal.api.Facade
import com.lightstick.types.Color
import com.lightstick.types.LSEffectPayload
import com.lightstick.events.EventRule
import com.lightstick.events.EventManager
import com.lightstick.ota.OtaManager
import com.lightstick.device.dto.DeviceInfo
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.parcelize.Parcelize

/**
 * Public representation of a LightStick BLE device.
 *
 * This parcelable DTO represents both scanned and bonded devices and exposes
 * high-level, device-scoped operations such as connecting, reading device info,
 * controlling LEDs, OTA, and registering event rules.
 *
 * API principles:
 *  - Methods return `Boolean` meaning "request was submitted to the system (true/false)".
 *  - Success/failure data is delivered via `Result<T>` callback when applicable.
 *  - No `onError` side-channel: failures are surfaced through `Result.failure(...)`
 *    (or simply by `false` when the request could not be submitted).
 *
 * @property mac  Bluetooth MAC address of the device.
 * @property name Device name (nullable, may vary per scan).
 * @property rssi Last known signal strength (RSSI), nullable for bonded entries.
 *
 * @since 1.0.0
 */
@Parcelize
data class Device(
    val mac: String,
    val name: String? = null,
    val rssi: Int? = null
) : Parcelable {

    // ------------------------------------------------------------------------
    // Connect / Disconnect
    // ------------------------------------------------------------------------

    /**
     * Connects to this device and provides a device-scoped [Controller].
     *
     * A [Controller] is created internally and passed to [onConnected].
     * After a successful connection, you may use Device-level convenience methods
     * like [sendColor], [sendEffect], [play], [requestMtu], [readBattery], etc.
     *
     * @param onConnected Invoked with a bound [Controller] on success.
     * @param onFailed    Invoked with the encountered [Throwable] on failure.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleConnectDevice
     */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(
        onConnected: (Controller) -> Unit,
        onFailed: (Throwable) -> Unit,
        onDeviceInfo: ((DeviceInfo) -> Unit)? = null
    ) {
        Facade.connect(
            mac = mac,
            onConnected = {
                // Build a transient device-scoped Controller that delegates to Facade.
                val ctl = object : Controller {
                    override val device: Device = this@Device

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun sendColor(color: Color, transition: Int) {
                        val t = transition.coerceIn(0, 255).toByte()
                        Facade.sendColorTo(
                            mac = mac,
                            packet4 = byteArrayOf(
                                color.r.toByte(),
                                color.g.toByte(),
                                color.b.toByte(),
                                t
                            )
                        )
                    }

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun sendEffect(payload: LSEffectPayload) {
                        Facade.sendEffectTo(mac = mac, bytes16 = payload.toByteArray())
                    }

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun play(frames: List<Pair<Long, ByteArray>>) {
                        Facade.playEntries(mac = mac, frames = frames)
                    }

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun requestMtu(preferred: Int, onResult: (Result<Int>) -> Unit) {
                        Facade.requestMtu(mac, preferred, onResult)
                    }

                    // ------------------ Device Information ------------------

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun readDeviceName(onResult: (Result<String>) -> Unit) {
                        Facade.readDeviceName(mac, onResult)
                    }

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun readModelNumber(onResult: (Result<String>) -> Unit) {
                        Facade.readModelNumber(mac, onResult)
                    }

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun readFirmwareRevision(onResult: (Result<String>) -> Unit) {
                        Facade.readFirmwareRevision(mac, onResult)
                    }

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun readManufacturer(onResult: (Result<String>) -> Unit) {
                        Facade.readManufacturer(mac, onResult)
                    }

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun readMacAddress(onResult: (Result<String>) -> Unit) {
                        Facade.readMacAddress(mac, onResult)
                    }

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun readBattery(onResult: (Result<Int>) -> Unit) {
                        Facade.readBattery(mac, onResult)
                    }

                    // --------------------------- OTA -------------------------

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun startOta(
                        firmware: ByteArray,
                        onProgress: (Int) -> Unit,
                        onResult: (Result<Unit>) -> Unit
                    ) {
                        Facade.startOta(
                            mac = mac,
                            firmware = firmware,
                            onProgress = onProgress,
                            onResult = onResult
                        )
                    }

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun abortOta() {
                        Facade.abortOta(mac)
                    }
                }
                onConnected(ctl)

                if (onDeviceInfo != null) {
                    fetchDeviceInfo { info -> onDeviceInfo(info) }
                }
            },
            onFailed = onFailed
        )
    }

    /**
     * Disconnects from this device.
     *
     * @return `true` if the disconnect request was submitted; otherwise `false`.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleDisconnect
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect(): Boolean {
        return try {
            Facade.disconnect(mac)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------------
    // Bonding (system pairing)
    // ------------------------------------------------------------------------

    /**
     * Initiates system bonding (pairing) for THIS device.
     *
     * @param onDone Invoked when the system reports bonded (or was already bonded).
     * @return `true` if the bond request was submitted to the system; `false` otherwise.
     * @sample com.lightstick.samples.DeviceSamples.sampleBond
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun bond(
        onDone: (() -> Unit)? = null
    ): Boolean {
        return try {
            Facade.ensureBond(
                mac = mac,
                onDone = { onDone?.invoke() },
                onFailed = { /* swallowed: submission still returned true */ }
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Requests system unbond (remove pairing) for THIS device.
     *
     * @param onDone Invoked on success (when system confirms removal).
     * @return `true` if the unbond request was submitted; `false` otherwise.
     * @sample com.lightstick.samples.DeviceSamples.sampleUnbond
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun unbond(
        onDone: (() -> Unit)? = null
    ): Boolean {
        return try {
            Facade.removeBond(
                mac = mac,
                onResult = { result ->
                    result.onSuccess { onDone?.invoke() }
                    // onFailure is swallowed to keep API error-less; submission already true.
                }
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------------
    // Connection / Bond state
    // ------------------------------------------------------------------------

    /**
     * Checks if this device currently has an active connection.
     *
     * @return `true` if connected; otherwise `false`.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleIsConnected
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isConnected(): Boolean = Facade.isConnected(mac)

    /**
     * Checks if this device is system-bonded (paired).
     *
     * @return `true` if bonded; otherwise `false`.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleIsBonded
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isBonded(): Boolean = Facade.isBonded(mac)

    // ------------------------------------------------------------------------
    // Device-level convenience (Boolean-only + no onError)
    // ------------------------------------------------------------------------

    /**
     * Sends a 4-byte color packet [R, G, B, transition] to THIS device.
     *
     * @param color      Logical color (0..255 per channel).
     * @param transition Transition parameter, clamped to [0, 255].
     * @return `true` if the packet was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleSendColor
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendColor(
        color: Color,
        transition: Int
    ): Boolean {
        return try {
            if (!isConnected()) return false
            val t = transition.coerceIn(0, 255).toByte()
            Facade.sendColorTo(
                mac = mac,
                packet4 = byteArrayOf(color.r.toByte(), color.g.toByte(), color.b.toByte(), t)
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Sends a 16-byte effect payload to THIS device.
     *
     * @param payload 16-byte structured effect payload.
     * @return `true` if the frame was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleSendEffect
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendEffect(payload: LSEffectPayload): Boolean {
        return try {
            if (!isConnected()) return false
            Facade.sendEffectTo(mac = mac, bytes16 = payload.toByteArray())
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Streams timestamped frames to THIS device.
     *
     * Each frame is (timestampMs, 16B payload).
     *
     * @param frames Ordered list of frames to play.
     * @return `true` if the stream was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.samplePlayFrames
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun play(frames: List<Pair<Long, ByteArray>>): Boolean {
        return try {
            if (!isConnected()) return false
            Facade.playEntries(mac = mac, frames = frames)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Requests MTU negotiation with THIS device.
     *
     * The negotiated MTU should be observed via your Facade/VM/Flow pipeline.
     *
     * @param preferred Preferred MTU value.
     * @return `true` if the request was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleRequestMtu
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestMtu(preferred: Int): Boolean {
        return try {
            if (!isConnected()) return false
            Facade.requestMtu(mac, preferred) { /* observed elsewhere */ }
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------------
    // Device Information (Result<T>-only)
    // ------------------------------------------------------------------------

    /**
     * Reads DIS 2A00: Device Name from THIS device.
     *
     * @param onResult Called with `Result.success(name)` or `Result.failure(cause)`.
     * @return `true` if the read request was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleReadDeviceInfo
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readDeviceName(onResult: (Result<String>) -> Unit): Boolean =
        submitReadWithResult({ cb -> Facade.readDeviceName(mac, cb) }, onResult)

    /**
     * Reads DIS 2A24: Model Number from THIS device.
     *
     * @param onResult Called with `Result.success(modelNumber)` or `Result.failure(cause)`.
     * @return `true` if the read request was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleReadDeviceInfo
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readModelNumber(onResult: (Result<String>) -> Unit): Boolean =
        submitReadWithResult({ cb -> Facade.readModelNumber(mac, cb) }, onResult)

    /**
     * Reads DIS 2A26: Firmware Revision from THIS device.
     *
     * @param onResult Called with `Result.success(firmwareRevision)` or `Result.failure(cause)`.
     * @return `true` if the read request was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleReadDeviceInfo
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readFirmwareRevision(onResult: (Result<String>) -> Unit): Boolean =
        submitReadWithResult({ cb -> Facade.readFirmwareRevision(mac, cb) }, onResult)

    /**
     * Reads DIS 2A29: Manufacturer Name from THIS device.
     *
     * @param onResult Called with `Result.success(manufacturerName)` or `Result.failure(cause)`.
     * @return `true` if the read request was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleReadDeviceInfo
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readManufacturer(onResult: (Result<String>) -> Unit): Boolean =
        submitReadWithResult({ cb -> Facade.readManufacturer(mac, cb) }, onResult)

    /**
     * Reads a custom MAC Address characteristic from THIS device
     * (device-firmware specific; not part of standard DIS).
     *
     * @param onResult Called with `Result.success(macString)` or `Result.failure(cause)`.
     * @return `true` if the read request was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleReadDeviceInfo
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readMacAddress(onResult: (Result<String>) -> Unit): Boolean =
        submitReadWithResult({ cb -> Facade.readMacAddress(mac, cb) }, onResult)

    /**
     * Reads BAS 2A19: Battery Level (0..100) from THIS device.
     *
     * @param onResult Called with `Result.success(levelPercent)` or `Result.failure(cause)`.
     * @return `true` if the read request was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleReadDeviceInfo
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readBattery(onResult: (Result<Int>) -> Unit): Boolean =
        submitReadWithResult({ cb -> Facade.readBattery(mac, cb) }, onResult)

    /**
     * Reads 4 DIS fields (2A00, 2A24, 2A26, 2A29) in parallel and returns an aggregated DeviceInfo.
     * Values that fail to read will be null.
     *
     * @param onResult Callback invoked once all four reads have completed.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun fetchDeviceInfo(onResult: (DeviceInfo) -> Unit): Boolean {
        if (!isConnected()) return false

        var name: String? = null
        var model: String? = null
        var fw: String? = null
        var mfr: String? = null

        val total = 4
        val done = AtomicInteger(0)
        fun completeOne() {
            if (done.incrementAndGet() == total) {
                onResult(
                    DeviceInfo(
                        deviceName = name,
                        modelNumber = model,
                        firmwareRevision = fw,
                        manufacturer = mfr
                    )
                )
            }
        }

        // 기존 Result 콜백 기반 read API를 그대로 사용
        readDeviceName { r ->
            r.onSuccess { name = it }.onFailure { /* ignore, stays null */ }
            completeOne()
        }
        readModelNumber { r ->
            r.onSuccess { model = it }.onFailure { /* ignore */ }
            completeOne()
        }
        readFirmwareRevision { r ->
            r.onSuccess { fw = it }.onFailure { /* ignore */ }
            completeOne()
        }
        readManufacturer { r ->
            r.onSuccess { mfr = it }.onFailure { /* ignore */ }
            completeOne()
        }

        return true
    }


    // ------------------------------------------------------------------------
    // OTA (Result<Unit>-style completion)
    // ------------------------------------------------------------------------

    /**
     * Starts OTA on THIS device with the provided firmware image.
     *
     * @param firmware   Raw firmware bytes.
     * @param onProgress Optional progress callback (0..100).
     * @param onResult   Optional completion callback: `Result.success(Unit)` on success,
     *                   or `Result.failure(Throwable)` on failure.
     * @return `true` if the OTA session was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleStartOta
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startOta(
        firmware: ByteArray,
        onProgress: ((Int) -> Unit)? = null,
        onResult: ((Result<Unit>) -> Unit)? = null
    ): Boolean {
        return try {
            if (!isConnected()) return false
            OtaManager.startOta(
                device = this,
                firmwareBytes = firmware,
                onProgress = { p -> onProgress?.invoke(p.percent) },
                onResult = { r ->
                    if (onResult == null) return@startOta
                    if (r.ok) onResult(Result.success(Unit))
                    else onResult(Result.failure(IllegalStateException(r.message ?: "OTA failed")))
                }
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Aborts an ongoing OTA session on THIS device.
     *
     * @return `true` if the abort request was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     * @sample com.lightstick.samples.DeviceSamples.sampleAbortOta
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun abortOta(): Boolean {
        return try {
            if (!isConnected()) return false
            OtaManager.abortOta(this)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------------
    // Event API (device-scoped) — delegate to public EventManager
    // ------------------------------------------------------------------------

    /**
     * Registers [EventRule]s that apply **only to this device**.
     *
     * @param rules List of event rules to register for THIS device.
     * @sample com.lightstick.samples.DeviceSamples.sampleRegisterDeviceRules
     */
    @MainThread
    fun registerEventRules(rules: List<EventRule>) {
        try {
            EventManager.setDeviceRules(mac, rules)
        } catch (e: Exception) {
            android.util.Log.w("Device", "registerEventRules($mac) failed: ${e.message}", e)
        }
    }

    /**
     * Clears all event rules associated with THIS device.
     *
     * @sample com.lightstick.samples.DeviceSamples.sampleClearDeviceRules
     */
    @MainThread
    fun clearEventRules() {
        try {
            EventManager.clearDeviceRules(mac)
        } catch (e: Exception) {
            android.util.Log.w("Device", "clearEventRules($mac) failed: ${e.message}", e)
        }
    }

    /**
     * Returns the current device-scoped event rules for THIS device.
     *
     * @return List of [EventRule] currently registered for this MAC (may be empty).
     * @sample com.lightstick.samples.DeviceSamples.sampleGetDeviceRules
     */
    @MainThread
    fun getEventRules(): List<EventRule> {
        return try {
            EventManager.getDeviceRules(mac)
        } catch (e: Exception) {
            android.util.Log.w("Device", "getEventRules($mac) failed: ${e.message}", e)
            emptyList()
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private inline fun <T> submitReadWithResult(
        crossinline submit: (onResult: (Result<T>) -> Unit) -> Unit,
        crossinline onResult: (Result<T>) -> Unit
    ): Boolean {
        return try {
            if (!isConnected()) {
                onResult(Result.failure(IllegalStateException("Device($mac) not connected")))
                return false
            }
            submit { result -> onResult(result) }
            true
        } catch (t: Throwable) {
            onResult(Result.failure(t))
            false
        }
    }
}
