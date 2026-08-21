package com.lightstick.device

import android.Manifest
import android.os.Parcelable
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import com.lightstick.device.DeviceInfo
import com.lightstick.game.GameCmd
import com.lightstick.game.GameMode
import com.lightstick.game.GameResult
import com.lightstick.internal.api.Facade
import com.lightstick.ota.OtaManager
import com.lightstick.types.Color
import com.lightstick.types.Colors
import com.lightstick.types.EffectType
import com.lightstick.types.Group
import com.lightstick.types.GroupPalette
import com.lightstick.types.LSEffectPayload
import com.lightstick.types.MsgType
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
 *  - Methods return `Boolean` meaning **"request was submitted to the BLE stack"**, NOT
 *    "the operation succeeded on the device". A `true` return only guarantees the command
 *    was enqueued; actual BLE write success is not confirmed at this layer.
 *  - Success/failure data is delivered via `Result<T>` callback when applicable.
 *  - No `onError` side-channel: failures are surfaced through `Result.failure(...)`
 *    (or simply by `false` when the request could not be submitted, e.g. device not connected).
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
     *         Log.d(TAG, "Connected to ${info.modelName}")
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

                // Facade는 DIS 읽기 성공/실패/타임아웃 여부와 관계없이
                // onConnected() 호출 직전에 updateDeviceInfo를 완료함.
                // 따라서 onDeviceInfo 콜백은 항상 발동이 보장됨.
                if (onDeviceInfo != null) {
                    val cached = Facade.getInternalDeviceInfo(mac)
                    if (cached != null) {
                        onDeviceInfo(TypeMappers.toPublic(cached))
                    }
                }
            },
            onFailed = onFailed
        )
    }

    /**
     * Disconnects from this device.
     *
     * @return `true` if the disconnect request was submitted to the stack; `false` if an
     *         unexpected error prevented submission (e.g., internal state corruption).
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
     * @param onDone   Invoked when the system reports bonded (or was already bonded).
     * @param onFailed Invoked with the encountered [Throwable] if bonding fails.
     *                 If `null`, bond failures are silently ignored.
     * @return `true` if the bond request was submitted to the system; `false` otherwise.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun bond(
        onDone: (() -> Unit)? = null,
        onFailed: ((Throwable) -> Unit)? = null
    ): Boolean {
        return try {
            Facade.ensureBond(
                mac = mac,
                onDone = { onDone?.invoke() },
                onFailed = { t -> onFailed?.invoke(t) }
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
     * @return `true` if the packet was enqueued to the BLE write queue; `false` if the
     *         device is not connected or an error prevented enqueuing. Does **not** indicate
     *         that the device received or applied the color.
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
     * Sends a 20-byte effect payload to THIS device — including group-targeted control.
     * There is no separate group-control method: a group (or "everyone") target is just
     * [LSEffectPayload.groupMask] on an ordinary payload — see [Group] for the
     * `GRP1`..`GRP32` / `ALL_SINGLE` / `ALL_GROUPS` mask constants.
     *
     * @param payload 20-byte structured effect payload.
     * @return `true` if the payload was enqueued to the BLE write queue; `false` if the
     *         device is not connected or an error prevented enqueuing.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     *
     * @sample
     * ```kotlin
     * // Group 1 + group 3 together, in one simultaneous packet.
     * device.sendEffect(
     *     LSEffectPayload(Group.GRP1 or Group.GRP3, EffectType.ON, Colors.WHITE)
     * )
     *
     * // "Wave" (파도타기): sequencing groups 1..N is the app's responsibility — send once
     * // per group, spaced by your own visual-pacing interval (recommended 200-1000ms).
     * for (groupId in 1..groupCount) {
     *     device.sendEffect(LSEffectPayload(1L shl (groupId - 1), EffectType.BLINK, Colors.WHITE))
     *     delay(waveIntervalMs)
     * }
     *
     * // Every connected lightstick, regardless of group assignment (groupMask omitted -> ALL_SINGLE).
     * device.sendEffect(LSEffectPayload(effectType = EffectType.OFF, color = Colors.WHITE))
     * ```
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
     * - Pins every frame's msgType to EFFECT, so a frame accidentally built as Game/Group
     *   never collides with those message types during playback
     * - Increments effectIndex for new playback session
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
     * The SDK automatically increments effectIndex for device resynchronization.
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
     * Returns `true` if this device exposes the BAS Battery Level characteristic (0x2A19).
     *
     * Must be called after a successful [connect] (service discovery complete).
     * Returns `false` if the device is not connected or the characteristic is absent.
     * Use this to guard [readBattery] calls and battery monitoring UI.
     *
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun supportsBattery(): Boolean = Facade.supportsBattery(mac)

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
                fun String?.realOrNull() = takeUnless { it.isNullOrBlank() || it == "Unknown" }
                val advName = this.name.realOrNull()
                    ?: Facade.getCachedDeviceName(mac).realOrNull()
                onResult(
                    DeviceInfo(
                        modelName = name.realOrNull(),
                        deviceName = advName,
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

    // ------------------------------------------------------------------------
    // Game Mode
    // ------------------------------------------------------------------------

    /**
     * Returns `true` if this device's firmware exposes the game command (FF03) and
     * result notify (FF04) characteristics under LCS_SERVICE.
     *
     * Must be called after a successful [connect] (service discovery complete).
     * Returns `false` if the device is not connected or game characteristics are absent.
     *
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun supportsGameMode(): Boolean = Facade.supportsGameMode(mac)



    /**
     * Enables FF04 game result Notify. Sends no command by itself — pair with
     * [sendGameCmd]`(GameCmd.START, ...)` to actually start a game, or with
     * [sendGameCmd]`(GameCmd.TEAM_ASSIGN_END, ...)` to receive that command's aggregated
     * confirmation (Mode 4). [onResult] fires once per Notify; check [GameResult.cmdIndex] to
     * tell a real result ([GameResult.CMD_RESULT]) apart from a Mode 4 team-assignment
     * confirmation ([GameResult.CMD_TEAM_CONFIRM]).
     *
     * @param onResult Called for each [GameResult] Notify received from the relay.
     * @return `true` if the CCCD write was submitted; `false` if not connected.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setNotifyGameResults(onResult: (GameResult) -> Unit): Boolean {
        return try {
            if (!isConnected()) return false
            Facade.subscribeGameResults(mac) { subIndex, cmdIndex, redScore, blueScore, totalCount, wandId ->
                val gameMode = GameMode.fromSubIndex(subIndex) ?: return@subscribeGameResults
                onResult(
                    GameResult(
                        mode       = gameMode,
                        cmdIndex   = cmdIndex,
                        redScore   = redScore,
                        blueScore  = blueScore,
                        totalCount = totalCount,
                        wandId     = wandId
                    )
                )
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Sends a game command to FF03. Single entry point for every game command — which
     * parameters matter depends on [cmd]:
     * - [GameCmd.START]: [mode] + [level] (difficulty, or Mode 4 round count 1~3) + [option]
     *   (use [GAME_OPTION_RANDOM_TEAM] for Mode 3 random team, or Mode 4's per-round measure
     *   time in ms).
     * - [GameCmd.WINNER]: [mode] (Mode 1/2 only — [GameMode.TEAM_BATTLE] returns `false`) +
     *   [wandId].
     * - [GameCmd.TEAM_ASSIGN] / [GameCmd.TEAM_ASSIGN_END]: [level] as the team id (0=RED/1=BLUE);
     *   `mode` is ignored (always Mode 4).
     * - [GameCmd.STOP] / [GameCmd.CLEAR]: no extra params.
     *
     * Pass [onResult] to (re-)enable FF04 Notify before sending, exactly like the old
     * `startGame` did — harmless to pass on more than one call (e.g. every Mode 4 step), since
     * it just re-registers the same listener. Omit it on calls where the subscription from an
     * earlier [sendGameCmd] call (or a standalone [setNotifyGameResults]) is already active.
     *
     * Typical usage — single call, just like `startGame` before:
     * ```kotlin
     * device.sendGameCmd(GameCmd.START, mode = GameMode.SPEED_REACTION, level = GameLevel.NORMAL.value) { result ->
     *     if (result.cmdIndex == GameResult.CMD_RESULT && result.isWandIdValid && result.redScore == 5) {
     *         // wand result.wandId finished first
     *     }
     * }
     * ```
     * Mode 4 team assignment — subscribe once on the first call, reuse it after:
     * ```kotlin
     * device.sendGameCmd(GameCmd.TEAM_ASSIGN, level = 0) { result -> ... }  // RED starts, subscribes
     * // ... wands lock in as their buttons are pressed ...
     * device.sendGameCmd(GameCmd.TEAM_ASSIGN_END, level = 0)   // RED ends -> aggregated Notify
     * device.sendGameCmd(GameCmd.TEAM_ASSIGN, level = 1)       // BLUE starts
     * device.sendGameCmd(GameCmd.TEAM_ASSIGN_END, level = 1)   // BLUE ends -> aggregated Notify
     * device.sendGameCmd(GameCmd.START, mode = GameMode.TEAM_SIMULTANEOUS, level = 3, option = 5000)
     * ```
     *
     * @param cmd      Command to send.
     * @param mode     Game mode; required for [GameCmd.START] / [GameCmd.WINNER], ignored otherwise.
     * @param level    Meaning depends on [cmd] — see above. Default 0.
     * @param option   Meaning depends on [cmd] — see above. Default 0.
     * @param wandId   Winner's wand id; only used by [GameCmd.WINNER]. Default 0.
     * @param onResult If non-null, calls [setNotifyGameResults] with it before sending [cmd].
     * @return `true` if the command was enqueued; `false` if not connected, the Notify
     *         (re-)subscription failed, [GameCmd.WINNER] was requested for an unsupported mode,
     *         or an error prevented submission.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendGameCmd(
        cmd: GameCmd,
        mode: GameMode? = null,
        level: Int = 0,
        option: Int = 0,
        wandId: Int = 0,
        onResult: ((GameResult) -> Unit)? = null
    ): Boolean {
        if (cmd == GameCmd.WINNER && mode == GameMode.TEAM_BATTLE) return false
        return try {
            if (!isConnected()) return false
            if (onResult != null && !setNotifyGameResults(onResult)) return false
            when (cmd) {
                GameCmd.START -> Facade.sendGameReady(mac, mode?.subIndex ?: 0, level, option)
                GameCmd.STOP -> Facade.sendGameStop(mac)
                GameCmd.CLEAR -> Facade.sendGameClear(mac)
                GameCmd.WINNER -> Facade.sendGameWinner(mac, mode?.subIndex ?: 0, wandId)
                GameCmd.TEAM_ASSIGN -> Facade.sendGameTeamAssign(mac, level)
                GameCmd.TEAM_ASSIGN_END -> Facade.sendGameTeamAssignEnd(mac, level)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Disables FF04 game result Notify without sending any command to the device.
     *
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun clearNotifyGameResults() {
        try {
            Facade.unsubscribeGameResults(mac)
        } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------------------
    // Group Setting (Glowsync group mapping spec v2.0)
    // ------------------------------------------------------------------------

    /**
     * Broadcasts the **GroupSetup** ([MsgType.GROUP_SETUP]) "join group [groupId]" beacon while
     * the organizer holds the group screen open.
     *
     * This bypasses [sendEffect]'s timeline-stop / msgType-forcing behavior — required because
     * a GroupSetup frame's msgType must survive unmodified, which [sendEffect] would otherwise
     * stamp back to [MsgType.EFFECT]. Group *control* has no such requirement (it's an ordinary
     * [MsgType.EFFECT] frame with `groupMask` set) so it needs no separate method — just call
     * [sendEffect] like any other effect.
     *
     * Unassigned lightsticks blink [GroupPalette.colorFor] while this is being broadcast;
     * pressing the lightstick's button locks it to this group. There is no explicit "stop"
     * message — call this again with the next [groupId] when the organizer moves on.
     *
     * @param groupId Group to advertise (1..20 — see [GroupPalette] for the palette's current
     *        range).
     * @return `true` if the payload was enqueued to the BLE write queue; `false` if the
     *         device is not connected.
     * @throws IllegalArgumentException If [groupId] is outside 1..20.
     * @throws SecurityException If [Manifest.permission.BLUETOOTH_CONNECT] is missing.
     *
     * @sample
     * ```kotlin
     * // Organizer holds "join group N" open; app advances to the next group when ready.
     * for (groupId in 1..groupCount) {
     *     device.sendGroupSetting(groupId)
     *     delay(setupWindowMs)
     * }
     * ```
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendGroupSetting(groupId: Int): Boolean {
        require(groupId in GroupPalette.MIN_GROUP_ID..GroupPalette.MAX_GROUP_ID) {
            "groupId must be within ${GroupPalette.MIN_GROUP_ID}..${GroupPalette.MAX_GROUP_ID} for GroupSetup"
        }
        val payload = LSEffectPayload(
            groupMask = 1L shl (groupId - 1),
            effectType = EffectType.BLINK,
            color = GroupPalette.colorFor(groupId),
            backgroundColor = Colors.BLACK,
            msgType = MsgType.GROUP_SETUP,
            period = 6,
            spf = 100,
            randomColor = 0,
            randomDelay = 0,
            fade = 0,
            broadcasting = 0
        )
        return try {
            if (!isConnected()) return false
            Facade.sendGroupSettingTo(mac, payload.toByteArray())
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    companion object {
        /**
         * Pass as the `option` argument of [sendGameCmd]`(GameCmd.START, ...)` for
         * [GameMode.TEAM_BATTLE] to let the relay assign Red / Blue teams randomly
         * (spec §5, option = 0xFF).
         */
        const val GAME_OPTION_RANDOM_TEAM: Int = 0xFF
    }

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