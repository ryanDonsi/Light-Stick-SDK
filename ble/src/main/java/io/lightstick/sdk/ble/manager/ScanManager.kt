package io.lightstick.sdk.ble.manager

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import io.lightstick.sdk.ble.model.ScanResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages BLE scanning using BluetoothLeScanner and dispatches results.
 *
 * @property context Application context used for scanning
 */
class ScanManager(
    private val context: Context
) {

    /**
     * Android Bluetooth adapter used for accessing LE scanner.
     */
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Android BLE scanner instance.
     */
    private val bluetoothLeScanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    /**
     * Flow that holds the current list of scan results by device address.
     */
    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())

    /**
     * Public flow of scan results, updated live.
     */
    val scanResults: StateFlow<List<ScanResult>> = _scanResults

    /**
     * Optional external filter for BLE device names.
     *
     * This lambda allows dynamic filtering of scanned device names based on custom logic.
     * - If the lambda returns `true`, the device is included in the scan results.
     * - If it returns `false`, the device is ignored.
     *
     * Example:
     *   nameFilter = { name -> name.endsWith("LS", ignoreCase = true) }
     *
     * Default behavior: Accept all names.
     */
    var nameFilter: (String) -> Boolean = { true }

    /**
     * Internal map for tracking unique scan results.
     */
    private val scanResultMap = ConcurrentHashMap<String, ScanResult>()

    private var isScanning = false
    private val scanHandler = Handler(Looper.getMainLooper())

    /**
     * Stops scanning after this duration by default (in milliseconds).
     */
    private val scanDuration = 10_000L

    private val stopScanRunnable = Runnable {
        @SuppressLint("MissingPermission")
        stopScan()
    }


    /**
     * Starts BLE scan with default filters.
     *
     * @throws SecurityException if BLUETOOTH_SCAN permission is not granted
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        if (isScanning) return

        val scanner = bluetoothLeScanner ?: return
        scanResultMap.clear()
        _scanResults.value = emptyList()

        val scanFilters = listOf<ScanFilter>() // Optional: Add filters here
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(scanFilters, scanSettings, scanCallback)
        isScanning = true
        scanHandler.postDelayed(stopScanRunnable, scanDuration)
        Log.d("ScanManager", "BLE scan started")
    }
    /**
     * Stops BLE scanning manually or when timeout is reached.
     *
     * @throws SecurityException if BLUETOOTH_SCAN permission is not granted
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        if (!isScanning) return

        bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        Log.d("ScanManager", "BLE scan stopped")
    }

    /**
     * ScanCallback receives BLE advertisement packets from nearby devices.
     */
    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return

            @SuppressLint("MissingPermission")
            val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown"

            if (!nameFilter(name)) return

            val rssi = result.rssi
            val scanResult = ScanResult(name = name, address = address, rssi = rssi)

            scanResultMap[address] = scanResult
            _scanResults.value = scanResultMap.values.sortedBy { it.address }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
        override fun onBatchScanResults(results: List<android.bluetooth.le.ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("ScanManager", "BLE scan failed with error code $errorCode")
        }
    }
}
