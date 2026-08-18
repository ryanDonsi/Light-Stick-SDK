package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission

/**
 * Sends raw Group protocol frames (Glowsync group mapping spec v2.0) to FF02.
 *
 * Unlike [LedControlManager], writes here bypass the msgType rewrite and timeline
 * machinery: a GroupSetup frame's msgType (GROUP_SETUP) must survive unmodified — which
 * [LedControlManager] would otherwise stamp back to MUSIC — and group-targeted control
 * frames (msgType=MUSIC + `groupMask`, see `LSEffectPayload.Group` in the public module)
 * skip [LedControlManager]'s stopTimeline()/effectIndex auto-management.
 *
 * Sequencing multiple group-control sends into a "wave" (파도타기, group-by-group timing)
 * is the caller's responsibility — this class only ever sends one frame per call.
 */
internal class GroupControlManager(
    private val gattClient: GattClient
) {

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendPayload(bytes20: ByteArray): Boolean {
        require(bytes20.size == 20) { "Group payload must be 20 bytes" }
        return gattClient.writeCharacteristic(
            serviceUuid = UuidConstants.LCS_SERVICE,
            charUuid = UuidConstants.LCS_PAYLOAD,
            data = bytes20,
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            replaceIfSameKey = false,
            coalesceKey = null
        )
    }
}
