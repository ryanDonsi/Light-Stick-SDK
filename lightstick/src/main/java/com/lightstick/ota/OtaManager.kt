package com.lightstick.ota

import android.Manifest
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import com.lightstick.device.Device
import com.lightstick.internal.api.Facade
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Public entry point for performing OTA (Over-the-Air) firmware updates
 * on a **single** device. The Telink OTA protocol and GATT transport live
 * in the internal module and are accessed via [Facade].
 *
 * Additions in this version:
 * - Connection guard using [Facade.isConnected] before starting OTA (fast fail).
 * - Public [OtaStatus] enum and [state] flow to observe OTA state mapped
 *   from the internal ordinal-based state.
 *
 * Threading:
 * - Call from the main thread.
 *
 * Permissions:
 * - Requires BLUETOOTH_CONNECT permission for all functions.
 *
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.OtaSamples.sampleOtaFromBytes
 * @sample com.lightstick.samples.OtaSamples.sampleOtaWithOptions
 * @sample com.lightstick.samples.OtaSamples.sampleObserveState
 * @sample com.lightstick.samples.OtaSamples.sampleAbortOta
 */
object OtaManager {

    /**
     * Public OTA status enum exposed by the open module.
     *
     * ⚠️ IMPORTANT:
     * This enum **mirrors** the internal state ordering (ordinal-based mapping).
     * If the internal order changes, out-of-range ordinals will be safely mapped to [ERROR].
     */
    enum class OtaStatus {
        IDLE,            // Waiting/idle
        PREPARING,       // Preparing resources / pre-checks
        NEGOTIATING_MTU, // MTU negotiation in progress
        ENABLING_NOTIFY, // CCCD enable sequence in progress
        TRANSFERRING,    // Firmware data transfer in progress
        VERIFYING,       // Optional verification stage
        COMPLETED,       // Completed successfully
        ABORTED,         // Aborted by user/request
        ERROR            // Failed with an error
    }

    /** Lightweight progress DTO (0..100). */
    data class OtaProgress(val percent: Int)

    /** Final result DTO. */
    data class OtaResult(val ok: Boolean, val message: String? = null)

    /**
     * Starts OTA on the given [device] with in-memory [firmwareBytes].
     *
     * The internal Facade handles segmentation, MTU negotiation, enable-notify (CCCD),
     * and write queueing according to the Telink OTA sequence.
     *
     * Connection guard:
     * - This method checks [Facade.isConnected] first.
     * - If not connected, it returns immediately via [onResult] with a failure.
     *
     * @param device Target device (must be connected).
     * @param firmwareBytes Raw firmware image.
     * @param preferredMtu Optional MTU hint (default 247).
     * @param startOpcodes Optional Telink preamble/enable bytes (if your FW requires).
     * @param onProgress Progress callback (0..100).
     * @param onResult Completion callback.
     *
     * @throws IllegalStateException if the SDK is uninitialized.
     *
     * @sample com.lightstick.samples.OtaSamples.sampleOtaFromBytes
     * @sample com.lightstick.samples.OtaSamples.sampleOtaWithOptions
     */
    @JvmStatic
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startOta(
        device: Device,
        firmwareBytes: ByteArray,
        preferredMtu: Int = 247,
        startOpcodes: ByteArray? = null,
        onProgress: (OtaProgress) -> Unit = {},
        onResult: (OtaResult) -> Unit = {}
    ) {
        // Fast connection guard for better UX
        if (!Facade.isConnected(device.mac)) {
            onResult(OtaResult(false, "Device not connected: ${device.mac}"))
            return
        }

        // Delegate to internal Facade
        Facade.startOta(
            mac = device.mac,
            firmware = firmwareBytes,
            preferredMtu = preferredMtu,
            startOpcodes = startOpcodes,
            onProgress = { pct -> onProgress(OtaProgress(pct)) },
            onResult = { result ->
                result
                    .onSuccess { onResult(OtaResult(true, null)) }
                    .onFailure { e -> onResult(OtaResult(false, e.message)) }
            }
        )
    }

    /**
     * Aborts an ongoing OTA session on [device], if any.
     *
     * This call is safe to invoke even when no active session exists.
     *
     * @param device Target device.
     *
     * @sample com.lightstick.samples.OtaSamples.sampleAbortOta
     */
    @JvmStatic
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun abortOta(device: Device) {
        Facade.abortOta(device.mac)
    }

    /**
     * Returns a **hot** Flow of [OtaStatus] for the given [device].
     *
     * Internally, the Facade exposes an ordinal-based Flow<Int> tied to the internal state;
     * we map that ordinal to this public [OtaStatus]. Any unexpected ordinal will fallback to [ERROR].
     *
     * Usage:
     * ```
     * lifecycleScope.launch {
     *   OtaManager.state(device).collect { status ->
     *     when (status) {
     *       OtaStatus.TRANSFERRING -> ...
     *       OtaStatus.COMPLETED    -> ...
     *       OtaStatus.ERROR        -> ...
     *       else -> Unit
     *     }
     *   }
     * }
     * ```
     *
     * @sample com.lightstick.samples.OtaSamples.sampleObserveState
     * @sample com.lightstick.samples.OtaSamples.startObserveState
     */
    @JvmStatic
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun state(device: Device): Flow<OtaStatus> =
        Facade.otaState(device.mac).map { ordinal ->
            OtaStatus.entries.toTypedArray().getOrElse(ordinal) { OtaStatus.ERROR }
        }
}
