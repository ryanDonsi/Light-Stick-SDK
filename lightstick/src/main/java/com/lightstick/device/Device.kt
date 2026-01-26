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
import com.lightstick.device.DeviceInfo
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
     * Connects to this device.
     *
     * After a successful connection, you can use all Device methods like
     * [sendColor], [sendEffect], [loadTimeline], [updatePlaybackPosition], etc.
     *
     * @param onConnected Invoked on successful connection.
     * @param onFailed    Invoked with the encountered [Throwable] on failure.
     * @param onDeviceInfo Optional callback for device information (name, model, firmware, battery, etc.).
     *                     If provided, device info will be fetched automatically after connection.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     *
     * @sample
     * ```kotlin
     * // Basic connection with animation
     * device.connect(
     *     onConnected = {
     *         // ✅ 연결 성공! 여기서 초기 연출
     *         device.sendEffect(LSEffectPayload.Effects.blink(color = Colors.GREEN, period = 3))
     *     },
     *     onFailed = { error ->
     *         Log.e(TAG, "Connect failed: ${error.message}")
     *     }
     * )
     *
     * // Connection with device info
     * device.connect(
     *     onConnected = {
     *         device.sendColor(Colors.GREEN, transition = 10)
     *     },
     *     onDeviceInfo = { info ->
     *         // DeviceInfo 활용
     *         Log.d(TAG, "Connected to ${info.deviceName}")
     *         Log.d(TAG, "Battery: ${info.batteryLevel}%")
     *     }
     * )
     * ```
     */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(
        onConnected: () -> Unit = {},
        onFailed: (Throwable) -> Unit = {},
        onDeviceInfo: ((DeviceInfo) -> Unit)? = null
    ) {
        Facade.connect(
            mac = mac,
            onConnected = {
                onConnected()

                // onDeviceInfo가 제공된 경우에만 DeviceInfo 조회
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
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isConnected(): Boolean = Facade.isConnected(mac)

    /**
     * Checks if this device is system-bonded (paired).
     *
     * @return `true` if bonded; otherwise `false`.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isBonded(): Boolean = Facade.isBonded(mac)

    // ------------------------------------------------------------------------
    // LED Control
    // ------------------------------------------------------------------------

    /**
     * Sends a 4-byte color packet [R, G, B, transition] to THIS device.
     *
     * @param color      Logical color (0..255 per channel).
     * @param transition Transition parameter, clamped to [0, 255].
     * @return `true` if the packet was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
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
     * Sends a 20-byte effect payload to THIS device.
     *
     * @param payload 20-byte structured effect payload.
     * @return `true` if the frame was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendEffect(payload: LSEffectPayload): Boolean {
        return try {
            if (!isConnected()) return false
            Facade.sendEffectTo(mac = mac, bytes20 = payload.toByteArray())
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Streams timestamped frames to THIS device (legacy API).
     *
     * Each frame is (timestampMs, 20B payload).
     *
     * @param frames Ordered list of frames to play.
     * @return `true` if the stream was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
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

    // ------------------------------------------------------------------------
    // Timeline Playback (Music Sync)
    // ------------------------------------------------------------------------

    /**
     * Loads an EFX timeline for music-synchronized playback.
     *
     * The SDK automatically:
     * - Recalculates effectIndex to be sequential (1, 2, 3, ...)
     * - Increments syncIndex for new playback session
     * - Manages timeline state internally
     *
     * @param frames Timeline entries [(timestampMs, 20B payload), ...]
     * @return true if the request was submitted; false otherwise.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is missing.
     *
     * @sample
     * ```kotlin
     * val efx = Efx.read(musicFile)
     * device.loadTimeline(efx.body.toFrames())
     * ```
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun loadTimeline(frames: List<Pair<Long, ByteArray>>): Boolean {
        return try {
            if (!isConnected()) return false
            Facade.loadTimeline(mac, frames)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Updates the current music playback position.
     *
     * Call this periodically (recommended: every 100ms) with the current music position.
     * The SDK internally sends each effect at the precise timing.
     *
     * @param currentPositionMs Current position in milliseconds
     * @return true if the request was submitted; false otherwise.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is missing.
     *
     * @sample
     * ```kotlin
     * // In your music player loop (every 100ms)
     * device.updatePlaybackPosition(player.currentPosition)
     * ```
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun updatePlaybackPosition(currentPositionMs: Long): Boolean {
        return try {
            if (!isConnected()) return false
            Facade.updatePlaybackPosition(mac, currentPositionMs)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Pauses effect transmission.
     *
     * Timeline tracking continues internally, but BLE transmission is suspended.
     * When resumed, the SDK will automatically resync with the device.
     *
     * @return true if the request was submitted; false otherwise.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is missing.
     *
     * @sample
     * ```kotlin
     * // User toggles effects OFF
     * device.pauseEffects()
     * ```
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun pauseEffects(): Boolean {
        return try {
            if (!isConnected()) return false
            Facade.pauseEffects(mac)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Resumes effect transmission.
     *
     * The SDK automatically increments syncIndex for device resynchronization.
     *
     * @return true if the request was submitted; false otherwise.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is missing.
     *
     * @sample
     * ```kotlin
     * // User toggles effects ON
     * device.resumeEffects()
     * ```
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun resumeEffects(): Boolean {
        return try {
            if (!isConnected()) return false
            Facade.resumeEffects(mac)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Stops timeline playback completely and clears the timeline.
     *
     * To restart, call [loadTimeline] again.
     *
     * @return true if the request was submitted; false otherwise.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is missing.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stopTimeline(): Boolean {
        return try {
            if (!isConnected()) return false
            Facade.stopTimeline(mac)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if timeline is loaded and effects are currently being transmitted.
     *
     * @return true if playing, false otherwise.
     * @throws SecurityException If BLUETOOTH_CONNECT permission is missing.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isTimelinePlaying(): Boolean {
        return try {
            if (!isConnected()) return false
            Facade.isTimelinePlaying(mac)
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------------
    // MTU Negotiation
    // ------------------------------------------------------------------------

    /**
     * Requests MTU negotiation with THIS device.
     *
     * @param preferred Preferred MTU value.
     * @return `true` if the request was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
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
    // Device Information
    // ------------------------------------------------------------------------

    /**
     * Reads DIS 2A00: Device Name from THIS device.
     *
     * @param onResult Called with `Result.success(name)` or `Result.failure(cause)`.
     * @return `true` if the read request was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readDeviceName(onResult: (Result<String>) -> Unit): Boolean =
        submitReadWithResult({ cb -> Facade.readDeviceName(mac, cb) }, onResult)

    /**
     * Reads DIS 2A24: Model Number from THIS device.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readModelNumber(onResult: (Result<String>) -> Unit): Boolean =
        submitReadWithResult({ cb -> Facade.readModelNumber(mac, cb) }, onResult)

    /**
     * Reads DIS 2A26: Firmware Revision from THIS device.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readFirmwareRevision(onResult: (Result<String>) -> Unit): Boolean =
        submitReadWithResult({ cb -> Facade.readFirmwareRevision(mac, cb) }, onResult)

    /**
     * Reads DIS 2A29: Manufacturer Name from THIS device.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readManufacturer(onResult: (Result<String>) -> Unit): Boolean =
        submitReadWithResult({ cb -> Facade.readManufacturer(mac, cb) }, onResult)

    /**
     * Reads custom MAC Address characteristic from THIS device.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readMacAddress(onResult: (Result<String>) -> Unit): Boolean =
        submitReadWithResult({ cb -> Facade.readMacAddress(mac, cb) }, onResult)

    /**
     * Reads BAS 2A19: Battery Level (0..100) from THIS device.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readBattery(onResult: (Result<Int>) -> Unit): Boolean =
        submitReadWithResult({ cb -> Facade.readBattery(mac, cb) }, onResult)

    /**
     * Reads multiple device info fields in parallel and returns aggregated DeviceInfo.
     *
     * @param onResult Callback invoked once all reads have completed.
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
                        manufacturer = mfr,
                        macAddress = mac,
                        isConnected = true,
                        lastUpdated = System.currentTimeMillis()
                    )
                )
            }
        }

        readDeviceName { r ->
            r.onSuccess { name = it }
            completeOne()
        }
        readModelNumber { r ->
            r.onSuccess { model = it }
            completeOne()
        }
        readFirmwareRevision { r ->
            r.onSuccess { fw = it }
            completeOne()
        }
        readManufacturer { r ->
            r.onSuccess { mfr = it }
            completeOne()
        }

        return true
    }

    // ------------------------------------------------------------------------
    // OTA
    // ------------------------------------------------------------------------

    /**
     * Starts OTA on THIS device with the provided firmware image.
     *
     * @param firmware   Raw firmware bytes.
     * @param onProgress Optional progress callback (0..100).
     * @param onResult   Optional completion callback.
     * @return `true` if the OTA session was submitted; `false` otherwise.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
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
    // Event API (device-scoped)
    // ------------------------------------------------------------------------

    /**
     * Registers event rules that apply **only to this device**.
     *
     * @param rules List of event rules to register for THIS device.
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
     * @return List of event rules currently registered (may be empty).
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