package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import androidx.annotation.RequiresPermission
import com.lightstick.internal.util.Log

/**
 * Sends Control frames (BLE protocol v2.2 §2.6, msgType=3, layout C) to FF0A.
 *
 * Layout C (20 bytes):
 *   Offset 0     msgType     (u8)      – always 3 (CONTROL)
 *   Offset 1-4   groupMask   (u32 LE)  – 0 = every wand, 0xFFFFFFFF = every grouped wand, bit(N-1) = group N
 *   Offset 5     cmdIndex    (u8)      – SHOW_ENTER(1) / SHOW_EXIT(2) / PAUSE(3) / RESUME(4) / SYSTEM_OFF(5)
 *   Offset 6-16  reserved    (0)
 *   Offset 17    effectIndex (u8)      – dedup sequence; must change for every new command (resends included)
 *   Offset 18-19 reserved    (0)
 *
 * Written with Write With Response (spec §5). Because an identical 20-byte frame is dropped as a
 * duplicate by relays / masters / slaves, [effectIndex] is advanced on every [send].
 */
internal class ControlManager(private val gattClient: GattClient) {

    companion object {
        private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
    }

    @Volatile private var effectIndex: Int = 0

    /** True if the connected device exposes FF0A under LCS_SERVICE. Call after service discovery. */
    fun isControlSupported(): Boolean =
        gattClient.hasCharacteristic(UuidConstants.LCS_SERVICE, UuidConstants.LCS_CONTROL)

    @Synchronized
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun send(cmdIndex: Int, groupMask: Long): Boolean {
        effectIndex = ControlFrame.nextEffectIndex(effectIndex)
        val frame = ControlFrame.build(cmdIndex, groupMask, effectIndex)
        Log.d("[ControlManager] FF0A TX cmdIndex=$cmdIndex groupMask=0x${"%08X".format(groupMask)} effectIndex=$effectIndex raw : ${frame.toHex()}")
        return gattClient.writeCharacteristic(
            serviceUuid = UuidConstants.LCS_SERVICE,
            charUuid = UuidConstants.LCS_CONTROL,
            data = frame,
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
    }
}
