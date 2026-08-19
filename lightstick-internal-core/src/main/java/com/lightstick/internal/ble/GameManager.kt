package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * Game command sender and result parser for the relay/master wand game protocol
 * (protocol v2.0, "레이아웃 B" — `LE_GAME_PATLOAD_T`, msgType=1/GAMEMODE).
 *
 * Writes 20-byte command payloads to FF03 (LCS_GAME_CMD) and subscribes to
 * FF04 (LCS_GAME_RESULT) Notify to receive per-wand game results.
 *
 * Byte layout — the same 20-byte struct is reused for both directions:
 * downlink (app→wand command, consumed by `game_on_receive()`) and uplink
 * (wand→relay result, filled by `game_result_tx()`/`game_result_tx_m1()`):
 *   Offset 0      msgType   (u8)      – always 1 (GAMEMODE)
 *   Offset 1      subIndex  (u8)      – game mode 1~4
 *   Offset 2      cmdIndex  (u8)      – READY(1) / STOP(3) / CLEAR(4) / RESULT(5) / WINNER(6)
 *   Offset 3      level     (u8)      – Mode1/2/3 difficulty, Mode4 round count / team id
 *   Offset 4-5    option    (u16 LE)  – Mode3 random team (0xFF), Mode4 READY measure ms (<=8000)
 *   Offset 6      result    (u8)      – uplink only: per-wand score. Downlink (command): 0
 *   Offset 7-8    msgId     (u16 LE)  – uplink only: burst-dedup sequence number (802.15.4
 *                                       repeats results 3x; relay dedups before forwarding via
 *                                       FF04). Separate from effectIndex (downlink dedup) —
 *                                       never merge the two.
 *   Offset 9-10   wandId    (u16 LE)  – winner / reporting wand id
 *   Offset 11-17  reserved  (7 bytes)
 *   Offset 18-19  effectIndex (u16 LE) – downlink dedup only
 */
internal class GameManager(private val gattClient: GattClient) {

    companion object {
        private const val TAG = "GameManager"
        private const val MSG_TYPE_GAME_MODE = 1
        private const val CMD_READY  = 1
        private const val CMD_STOP   = 3
        private const val CMD_CLEAR  = 4
        private const val CMD_WINNER = 6

        private fun ByteArray.toHex(): String =
            joinToString(" ") { "%02X".format(it) }
    }

    // -------------------------------------------------------------------------
    // Capability check
    // -------------------------------------------------------------------------

    /**
     * Returns true if the connected device exposes both FF03 (LCS_GAME_CMD) and
     * FF04 (LCS_GAME_RESULT) under LCS_SERVICE. Must be called after service discovery.
     */
    fun isGameModeSupported(): Boolean =
        gattClient.hasCharacteristic(UuidConstants.LCS_SERVICE, UuidConstants.LCS_GAME_CMD) &&
        gattClient.hasCharacteristic(UuidConstants.LCS_SERVICE, UuidConstants.LCS_GAME_RESULT)

    // -------------------------------------------------------------------------
    // Notification subscription
    // -------------------------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun subscribeResults(onResult: (subIndex: Int, result: Int, msgId: Int, wandId: Int) -> Unit) {
        gattClient.addNotificationListener(UuidConstants.LCS_GAME_RESULT) { bytes ->
            Log.d(TAG, "FF04 RX [${bytes.size}B] raw : ${bytes.toHex()}")

            val parsed = parseResult(bytes)
            if (parsed == null) {
                Log.w(TAG, "FF04 RX parse failed (size=${bytes.size}, need >=11)")
                return@addNotificationListener
            }
            val (si, result, msgId, wandId) = parsed
            Log.d(TAG, "FF04 RX parsed : subIndex=$si  result=$result  msgId=0x%04X  wandId=0x%04X".format(msgId, wandId))
            if (si !in 1..4) Log.w(TAG, "FF04 RX unexpected subIndex=$si (expected 1~4)")
            if (wandId == 0x0000 || wandId == 0xFFFF) Log.w(TAG, "FF04 RX wandId=0x%04X is invalid sentinel".format(wandId))
            onResult(si, result, msgId, wandId)
        }
        gattClient.setCharacteristicNotification(
            serviceUuid = UuidConstants.LCS_SERVICE,
            charUuid    = UuidConstants.LCS_GAME_RESULT,
            enable      = true,
            onResult    = { result ->
                result.onSuccess { Log.d(TAG, "FF04 CCCD subscribe OK") }
                result.onFailure { Log.w(TAG, "FF04 CCCD subscribe FAILED: ${it.message}") }
            }
        )
    }

    fun unsubscribeResults() {
        gattClient.removeNotificationListener(UuidConstants.LCS_GAME_RESULT)
    }

    // -------------------------------------------------------------------------
    // Game commands
    // -------------------------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendReady(subIndex: Int, level: Int, option: Int): Boolean {
        val payload = buildPayload(subIndex, CMD_READY, level, option)
        Log.d(TAG, "FF03 TX READY subIndex=$subIndex level=$level option=0x%02X".format(option))
        Log.d(TAG, "FF03 TX raw : ${payload.toHex()}")
        return writeGameCmd(payload)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendStop(): Boolean {
        val payload = buildPayload(0, CMD_STOP, 0, 0)
        Log.d(TAG, "FF03 TX STOP raw : ${payload.toHex()}")
        return writeGameCmd(payload)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendClear(): Boolean {
        val payload = buildPayload(0, CMD_CLEAR, 0, 0)
        Log.d(TAG, "FF03 TX CLEAR raw : ${payload.toHex()}")
        return writeGameCmd(payload)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendWinner(subIndex: Int, winnerWandId: Int): Boolean {
        val payload = buildWinnerPayload(subIndex, winnerWandId)
        Log.d(TAG, "FF03 TX WINNER subIndex=$subIndex winnerWandId=0x%04X".format(winnerWandId))
        Log.d(TAG, "FF03 TX raw : ${payload.toHex()}")
        return writeGameCmd(payload)
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun writeGameCmd(payload: ByteArray): Boolean =
        gattClient.writeCharacteristic(
            serviceUuid = UuidConstants.LCS_SERVICE,
            charUuid    = UuidConstants.LCS_GAME_CMD,
            data        = payload,
            writeType   = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )

    private fun buildPayload(subIndex: Int, cmdIndex: Int, level: Int, option: Int): ByteArray =
        ByteArray(20).also { buf ->
            buf[0] = MSG_TYPE_GAME_MODE.toByte()
            buf[1] = subIndex.toByte()
            buf[2] = cmdIndex.toByte()
            buf[3] = level.toByte()
            putU16LE(buf, 4, option)
        }

    private fun buildWinnerPayload(subIndex: Int, winnerWandId: Int): ByteArray =
        ByteArray(20).also { buf ->
            buf[0] = MSG_TYPE_GAME_MODE.toByte()
            buf[1] = subIndex.toByte()
            buf[2] = CMD_WINNER.toByte()
            putU16LE(buf, 9, winnerWandId)
        }

    private data class ResultFields(val subIndex: Int, val result: Int, val msgId: Int, val wandId: Int)

    /** Returns null if bytes are too short to parse (need offsets 0-10). */
    private fun parseResult(bytes: ByteArray): ResultFields? {
        if (bytes.size < 11) return null
        val subIndex = bytes[1].toInt() and 0xFF
        val result   = bytes[6].toInt() and 0xFF
        val msgId    = getU16LE(bytes, 7)
        val wandId   = getU16LE(bytes, 9)
        return ResultFields(subIndex, result, msgId, wandId)
    }

    private fun putU16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun getU16LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
}
