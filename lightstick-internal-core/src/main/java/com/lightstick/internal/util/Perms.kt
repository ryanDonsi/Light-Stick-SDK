package com.lightstick.internal.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permission gate helpers for BLE operations.
 *
 * These helpers make Lint happy (explicit runtime checks)
 * and provide a single place to evolve permission policy later.
 */
internal object Perms {

    /**
     * Ensures BLUETOOTH_CONNECT is granted at runtime.
     *
     * @param context Any context (applicationContext recommended).
     * @throws SecurityException if the permission is not granted.
     *
     * @sample
     * // before any GATT operation:
     * Perms.ensureBtConnect(appContext)
     */
    fun ensureBtConnect(context: Context) {
        // Before Android 12 (API 31) BLUETOOTH_CONNECT does not exist: checkSelfPermission would
        // always report DENIED. Legacy BLUETOOTH/BLUETOOTH_ADMIN are install-time permissions
        // (declared in this module's manifest), so there is nothing to check at runtime.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("BLUETOOTH_CONNECT permission missing at runtime")
        }
    }

    /**
     * Ensures BLUETOOTH_SCAN is granted at runtime.
     *
     * @param context Any context (applicationContext recommended).
     * @throws SecurityException if the permission is not granted.
     *
     * @sample
     * // before startScan:
     * Perms.ensureBtScan(appContext)
     */
    fun ensureBtScan(context: Context) {
        // Before Android 12 (API 31) BLUETOOTH_SCAN does not exist; scanning requires location
        // permission instead (FINE from API 29, FINE or COARSE on API 26-28).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val fine = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (fine || (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && coarse)) return
            throw SecurityException("Location permission missing at runtime (required for BLE scan before Android 12)")
        }
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("BLUETOOTH_SCAN permission missing at runtime")
        }
    }
}
