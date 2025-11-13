package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
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
                // No-op; caller can restart if needed.
            }
        }

        scanner.startScan(callback)
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
}
