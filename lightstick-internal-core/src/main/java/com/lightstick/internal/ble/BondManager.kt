package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission

/**
 * Stateless bonding utilities.
 */
internal class BondManager {

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun ensureBond(context: Context, mac: String, onDone: () -> Unit, onFailed: (Throwable) -> Unit) {
        val dev = device(context, mac) ?: return onFailed(IllegalArgumentException("No device"))
        runCatching {
            if (dev.bondState == BluetoothDevice.BOND_BONDED) true else dev.createBond()
        }.onSuccess { ok ->
            if (ok) onDone() else onFailed(IllegalStateException("createBond=false"))
        }.onFailure(onFailed)
    }

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun removeBond(context: Context, mac: String, onResult: (Result<Unit>) -> Unit) {
        val dev = device(context, mac) ?: return onResult(Result.failure(IllegalArgumentException("No device")))
        runCatching {
            val m = dev.javaClass.getMethod("removeBond")
            val ok = (m.invoke(dev) as? Boolean) == true
            if (ok) Result.success(Unit) else Result.failure(IllegalStateException("removeBond=false"))
        }.onSuccess(onResult).onFailure { onResult(Result.failure(it)) }
    }

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun listBonded(context: Context, onResult: (Result<List<Pair<String, String?>>>) -> Unit) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return onResult(Result.failure(IllegalStateException("No adapter")))
        runCatching { adapter.bondedDevices?.map { it.address to it.name } ?: emptyList() }
            .onSuccess { onResult(Result.success(it)) }
            .onFailure { onResult(Result.failure(it)) }
    }

    /**
     * Returns bonded devices synchronously.
     *
     * `adapter.bondedDevices` is a synchronous Android API call; this method
     * returns the result directly without a callback.
     *
     * @return List of (MAC, name) pairs, empty if adapter is unavailable or an error occurs.
     */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun listBondedSync(context: Context): List<Pair<String, String?>> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return emptyList()
        return runCatching { adapter.bondedDevices?.map { it.address to it.name } ?: emptyList() }
            .getOrElse { emptyList() }
    }

    private fun device(context: Context, mac: String): BluetoothDevice? {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return adapter?.getRemoteDevice(mac)
    }
}
