package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import androidx.annotation.RequiresPermission

/**
 * Game command sender and result parser for the relay/master wand game protocol.
 *
 * Writes 20-byte command payloads to FF03 (LCS_GAME_CMD) and subscribes to
 * FF04 (LCS_GAME_RESULT) Notify to receive game results.
 *
 * Packet format (spec §9.2 / §7.1):
 *   Offset 0-1  effectIndex = 0x0005 (little-endian)
 *   Offset 2-3  subIndex    = game mode (1/2/3)
 *   Offset 4-5  cmdIndex    = READY(1) / STOP(3) / CLEAR(4) / RESULT(5)
 *   Offset 6-7  level       = 1=easy / 2=normal / 3=hard
 *   Offset 8-9  option      = 0 (Mode1/2) / 0xFF (Mode3 random team)
 *   Offset 10-19 reserved   = 0x00
 */
internal class GameManager(private val gattClient: GattClient) {

    companion object {
        private const val EFFECT_INDEX_GAME = 0x0005
        private const val CMD_READY  = 1
        private const val CMD_STOP   = 3
        private const val CMD_CLEAR  = 4
    }

    // -------------------------------------------------------------------------
    // Notification subscription
    // -------------------------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun subscribeResults(onResult: (subIndex: Int, redScore: Int, blueScore: Int, totalCount: Int, wandId: Int) -> Unit) {
        gattClient.addNotificationListener(UuidConstants.LCS_GAME_RESULT) { bytes ->
            parseResult(bytes)?.let { (si, r, b, t, w) -> onResult(si, r, b, t, w) }
        }
        gattClient.setCharacteristicNotification(
            serviceUuid = UuidConstants.LCS_SERVICE,
            charUuid    = UuidConstants.LCS_GAME_RESULT,
            enable      = true,
            onResult    = {}
        )
    }

    fun unsubscribeResults() {
        gattClient.removeNotificationListener(UuidConstants.LCS_GAME_RESULT)
    }

    // -------------------------------------------------------------------------
    // Game commands
    // -------------------------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendReady(subIndex: Int, level: Int, option: Int): Boolean =
        writeGameCmd(buildPayload(subIndex, CMD_READY, level, option))

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendStop(): Boolean =
        writeGameCmd(buildPayload(0, CMD_STOP, 0, 0))

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendClear(): Boolean =
        writeGameCmd(buildPayload(0, CMD_CLEAR, 0, 0))

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private fun writeGameCmd(payload: ByteArray): Boolean =
        gattClient.writeCharacteristic(
            serviceUuid = UuidConstants.LCS_SERVICE,
            charUuid    = UuidConstants.LCS_GAME_CMD,
            data        = payload,
            writeType   = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )

    private fun buildPayload(subIndex: Int, cmdIndex: Int, level: Int, option: Int): ByteArray =
        ByteArray(20).also { buf ->
            putU16LE(buf, 0, EFFECT_INDEX_GAME)
            putU16LE(buf, 2, subIndex)
            putU16LE(buf, 4, cmdIndex)
            putU16LE(buf, 6, level)
            putU16LE(buf, 8, option)
        }

    /** Returns null if bytes are too short to parse. wandId lives at offset 14 per spec §7.1. */
    private fun parseResult(bytes: ByteArray): Array<Int>? {
        if (bytes.size < 12) return null
        val subIndex   = getU16LE(bytes, 2)
        val redScore   = getU16LE(bytes, 6)
        val blueScore  = getU16LE(bytes, 8)
        val totalCount = getU16LE(bytes, 10)
        val wandId     = if (bytes.size >= 16) getU16LE(bytes, 14) else 0
        return arrayOf(subIndex, redScore, blueScore, totalCount, wandId)
    }

    private fun putU16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun getU16LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
}
