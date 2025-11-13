package com.lightstick.internal.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("BLUETOOTH_SCAN permission missing at runtime")
        }
    }
}
