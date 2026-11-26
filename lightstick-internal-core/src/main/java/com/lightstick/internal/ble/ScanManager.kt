package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import com.lightstick.internal.util.Log
import com.lightstick.internal.util.Perms

/**
 * Stateless scanner wrapper. Reports (mac, name?, rssi?).
 *
 * Notes:
 * - No Context is retained as a field (avoids memory-leak lint).
 * - Caller passes a Context for each start/stop call; internally we use applicationContext.
 */
internal class ScanManager {

    private var callback: ScanCallback? = null

    /**
     * Starts scanning and invokes [onDeviceFound] for every result.
     *
     * @param context Any context; applicationContext will be used internally.
     * @param onDeviceFound (mac, name?, rssi?) callback for each advertisement.
     * @throws SecurityException if BLUETOOTH_SCAN not granted.
     */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun start(context: Context, onDeviceFound: (mac: String, name: String?, rssi: Int?) -> Unit) {
        val appContext = context.applicationContext
        Perms.ensureBtScan(appContext)

        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter ?: throw IllegalStateException("BluetoothAdapter is null")
        val scanner = adapter.bluetoothLeScanner ?: throw IllegalStateException("BluetoothLeScanner is null")

        // Ensure single active callback
        stop(appContext)

        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val mac = device.address ?: return
                val rssi = result.rssi

                // Device name may require BLUETOOTH_CONNECT on some stacks — wrap defensively.
                val name: String? = try {
                    device.name
                } catch (_: SecurityException) {
                    null
                }

                onDeviceFound(mac, name, rssi)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) onScanResult(0, r)
            }

            override fun onScanFailed(errorCode: Int) {
                // Most common: SCAN_FAILED_APPLICATION_REGISTRATION_FAILED / "scanning too
                // frequently" (Android 7+: more than 5 starts in 30 s). Nothing will be found;
                // make it visible instead of failing silently. The caller can start again later.
                Log.w("[ScanManager] scan failed: ${scanErrorName(errorCode)} ($errorCode)")
                callback = null
            }
        }

        // Scans started by the SDK are short and user-initiated (1..300 s, default 3 s), so use
        // the lowest latency: devices show up in the first advertising intervals instead of after
        // the low-power mode's multi-second duty cycle. Results are delivered one by one.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        scanner.startScan(null, settings, callback)
    }

    /**
     * Stops an active scan.
     *
     * @param context Any context; applicationContext will be used internally.
     * @throws SecurityException if BLUETOOTH_SCAN not granted.
     */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stop(context: Context) {
        val cb = callback ?: return
        val appContext = context.applicationContext
        Perms.ensureBtScan(appContext)

        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        try {
            scanner.stopScan(cb) // may throw if permission missing -> guarded above
        } catch (_: SecurityException) {
            // ignore to keep stop() idempotent
        } finally {
            callback = null
        }
    }

    private fun scanErrorName(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APPLICATION_REGISTRATION_FAILED (scanning too frequently?)"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        else -> "UNKNOWN"
    }
}
