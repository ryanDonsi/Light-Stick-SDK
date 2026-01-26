@file:Suppress(
    "MissingPermission",          // Simplified demo: skips runtime permission handling
    "UNUSED_PARAMETER",
    "UNUSED_VARIABLE",
    "UNUSED_EXPRESSION"
)

package com.lightstick.samples

import android.content.Context
import android.util.Log
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.ota.OtaManager
import com.lightstick.ota.OtaManager.OtaProgress
import com.lightstick.ota.OtaManager.OtaResult
import com.lightstick.ota.OtaManager.OtaStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Usage samples for the public OTA API.
 *
 * All examples here are simplified for documentation and demonstration only.
 * In a real app:
 * - Always perform runtime permission checks (BLUETOOTH_CONNECT)
 * - Use lifecycle-aware coroutine scopes (e.g., lifecycleScope)
 * - Observe OTA progress and results through your own ViewModel or Flow
 */
object OtaSamples {

    // Demo-only coroutine scope (cancelled/restarted each run)
    private var scope: CoroutineScope? = null

    /**
     * Basic example of handling [OtaProgress].
     *
     * Demonstrates how you can receive progress callbacks
     * from [OtaManager.startOta] and print them or update a UI.
     */
    @JvmStatic
    fun sampleOtaProgress() {
        val progress = OtaProgress(percent = 42)
        Log.d("OtaSamples", "OTA progress sample: ${progress.percent}%")
        // In a real OTA flow, this object is delivered through onProgress callback
    }

    /**
     * Basic example of handling [OtaResult].
     *
     * Demonstrates how you can process completion results returned
     * by [OtaManager.startOta] and handle both success and failure cases.
     */
    @JvmStatic
    fun sampleOtaResult() {
        val successResult = OtaResult(ok = true, message = null)
        val failureResult = OtaResult(ok = false, message = "Checksum error")

        if (successResult.ok) {
            Log.d("OtaSamples", "OTA success result: ${successResult.message ?: "OK"}")
        }
        if (!failureResult.ok) {
            Log.w("OtaSamples", "OTA failed: ${failureResult.message}")
        }
        // In a real OTA flow, this result is returned via the onResult callback
    }

    /**
     * Example flow:
     * 1. Initialize the SDK
     * 2. Connect to a device
     * 3. Observe OTA state updates
     * 4. Start OTA from in-memory firmware bytes
     */
    @JvmStatic
    fun sampleOtaFromBytes(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF", name = "LightStick-OTA")

        if (!device.isConnected()) {
            device.connect(
                onConnected = {
                    Log.d("OtaSamples", "Connected. Start observing OTA state and launch OTA.")
                    startObserveState(device)
                    startOtaNow(device)
                },
                onFailed = { t ->
                    Log.w("OtaSamples", "Connection failed: ${t.message}", t)
                }
            )
        } else {
            startObserveState(device)
            startOtaNow(device)
        }
    }

    /** Observes OTA status changes as a Flow. */
    @JvmStatic
    fun sampleObserveState(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        startObserveState(device)
    }

    /** Requests to abort an ongoing OTA transfer. */
    @JvmStatic
    fun sampleAbortOta(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        OtaManager.abortOta(device)
        Log.d("OtaSamples", "Abort requested for ${device.mac}")
    }

    /** Demonstrates usage of optional parameters (preferredMtu, startOpcodes). */
    @JvmStatic
    fun sampleOtaWithOptions(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        startObserveState(device)

        val fw = ByteArray(8192) { 0x5A }
        val preamble = byteArrayOf(0x01, 0xFF.toByte())

        if (!device.isConnected()) {
            Log.w("OtaSamples", "Device not connected; connect first then retry.")
            return
        }

        OtaManager.startOta(
            device = device,
            firmwareBytes = fw,
            preferredMtu = 247,
            startOpcodes = preamble,
            onProgress = { p -> Log.d("OtaSamples", "Progress=${p.percent}%") },
            onResult = { r ->
                if (r.ok) Log.d("OtaSamples", "OTA completed successfully")
                else Log.w("OtaSamples", "OTA failed: ${r.message}")
            }
        )
    }

    // --------------------------------------------------------------------------------------------
    // Internal helpers used by the sample functions
    // --------------------------------------------------------------------------------------------

    /** Starts OTA with demo firmware bytes and logs progress/results. */
    private fun startOtaNow(device: Device) {
        val firmware = ByteArray(4096) { 0x7E }

        OtaManager.startOta(
            device = device,
            firmwareBytes = firmware,
            onProgress = { p ->
                Log.d("OtaSamples", "OTA progress: ${p.percent}%")
            },
            onResult = { r ->
                if (r.ok) {
                    Log.d("OtaSamples", "OTA completed successfully")
                } else {
                    Log.w("OtaSamples", "OTA error: ${r.message}")
                }
            }
        )
    }

    /** Starts observing OTA status updates for the given device. */
    private fun startObserveState(device: Device) {
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.Main + Job())

        scope?.launch {
            OtaManager.state(device).collectLatest { st ->
                when (st) {
                    OtaStatus.IDLE            -> Log.d("OtaSamples", "STATE: IDLE")
                    OtaStatus.PREPARING       -> Log.d("OtaSamples", "STATE: PREPARING")
                    OtaStatus.NEGOTIATING_MTU -> Log.d("OtaSamples", "STATE: NEGOTIATING_MTU")
                    OtaStatus.ENABLING_NOTIFY -> Log.d("OtaSamples", "STATE: ENABLING_NOTIFY")
                    OtaStatus.TRANSFERRING    -> Log.d("OtaSamples", "STATE: TRANSFERRING")
                    OtaStatus.VERIFYING       -> Log.d("OtaSamples", "STATE: VERIFYING")
                    OtaStatus.COMPLETED       -> Log.d("OtaSamples", "STATE: COMPLETED")
                    OtaStatus.ABORTED         -> Log.d("OtaSamples", "STATE: ABORTED")
                    OtaStatus.ERROR           -> Log.w("OtaSamples", "STATE: ERROR")
                }
            }
        }
    }
}