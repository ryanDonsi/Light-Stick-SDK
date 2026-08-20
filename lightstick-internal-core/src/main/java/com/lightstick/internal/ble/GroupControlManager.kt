package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import com.lightstick.internal.util.Log

/**
 * Sends raw GroupSetup frames (Glowsync group mapping spec v2.0, msgType=GROUP_SETUP) to FF02.
 *
 * Unlike [LedControlManager], writes here bypass the msgType rewrite and coalescing: a
 * GroupSetup frame's msgType must survive unmodified — which [LedControlManager] would
 * otherwise stamp back to EFFECT — and must not be silently replaced by a later coalesced
 * write while the organizer moves quickly between groups.
 *
 * Group *control* (msgType=EFFECT + `groupMask`, see `Group` in the public
 * module) is just an ordinary effect payload and goes through [LedControlManager] like any
 * other `Device.sendEffect` call — this class is only ever reached via
 * `Device.sendGroupSetting`, one frame per call.
 */
internal class GroupControlManager(
    private val gattClient: GattClient
) {

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendPayload(bytes20: ByteArray): Boolean {
        require(bytes20.size == 20) { "Group payload must be 20 bytes" }
        Log.d("[GroupControlManager] FF02 TX GroupSetup raw : ${bytes20.toHex()}")
        return gattClient.writeCharacteristic(
            serviceUuid = UuidConstants.LCS_SERVICE,
            charUuid = UuidConstants.LCS_PAYLOAD,
            data = bytes20,
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            replaceIfSameKey = false,
            coalesceKey = null
        )
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
}
