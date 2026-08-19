package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * Game command sender and result parser for the relay/master wand game protocol.
 *
 * FF03 (LCS_GAME_CMD, app→wand commands) and FF04 (LCS_GAME_RESULT, wand→app result Notify)
 * use **two different, independently-versioned layouts** — do not assume symmetry between them:
 *
 * FF03 command payload (protocol v2.0 "레이아웃 B", `LE_GAME_PATLOAD_T` downlink half):
 *   Offset 0     msgType   (u8)      – always 1 (GAMEMODE)
 *   Offset 1     subIndex  (u8)      – game mode 1~4
 *   Offset 2     cmdIndex  (u8)      – READY(1) / STOP(3) / CLEAR(4) / WINNER(6)
 *   Offset 3     level     (u8)      – Mode1/2/3 difficulty, Mode4 round count / team id
 *   Offset 4-5   option    (u16 LE)  – Mode3 random team (0xFF), Mode4 READY measure ms
 *   Offset 6-19  unused in this SDK (result/msgId/wandId/reserved/effectIndex — 0)
 *   (WINNER additionally sets wandId at offset 9-10.)
 *
 * FF04 result Notify payload (`GameMode_Spec_v2_6.docx` §2.4 — **unchanged**, still on the old
 * layout; FF03 moved to layout B but FF04 has not):
 *   Offset 0-1   effect_index (u16 LE) – fixed 0x0005
 *   Offset 2-3   sub_index    (u16 LE) – game mode 1~4
 *   Offset 4-5   cmd_index    (u16 LE) – RESULT=5 / TEAM_CONFIRM=8 (not parsed by this SDK)
 *   Offset 6-7   red_score    (u16 LE) – Mode1/2: individual score / Mode3/4: RED team total
 *   Offset 8-9   blue_score   (u16 LE) – Mode1/2: 0 / Mode3/4: BLUE team total
 *   Offset 10-11 total_count  (u16 LE) – cumulative wand count that has reported so far
 *   Offset 12-13 reserved0
 *   Offset 14-15 wand_id      (u16 LE) – Mode1/2: reporting wand's id / Mode3/4: 0x0000
 *   Offset 16-19 reserved1
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
    fun subscribeResults(onResult: (subIndex: Int, redScore: Int, blueScore: Int, totalCount: Int, wandId: Int) -> Unit) {
        gattClient.addNotificationListener(UuidConstants.LCS_GAME_RESULT) { bytes ->
            Log.d(TAG, "FF04 RX [${bytes.size}B] raw : ${bytes.toHex()}")

            val parsed = parseResult(bytes)
            if (parsed == null) {
                Log.w(TAG, "FF04 RX parse failed (size=${bytes.size}, need >=12)")
                return@addNotificationListener
            }
            val (si, r, b, t, w) = parsed
            Log.d(TAG, "FF04 RX parsed : subIndex=$si  redScore=$r  blueScore=$b  totalCount=$t  wandId=0x%04X".format(w))
            if (si !in 1..4) Log.w(TAG, "FF04 RX unexpected subIndex=$si (expected 1~4)")
            if (w == 0x0000 || w == 0xFFFF) Log.w(TAG, "FF04 RX wandId=0x%04X is invalid sentinel".format(w))
            onResult(si, r, b, t, w)
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
    // Game commands (FF03, layout B)
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

    /** FF03 layout B: msgType(0)/subIndex(1)/cmdIndex(2)/level(3)/option(4-5 u16). */
    private fun buildPayload(subIndex: Int, cmdIndex: Int, level: Int, option: Int): ByteArray =
        ByteArray(20).also { buf ->
            buf[0] = MSG_TYPE_GAME_MODE.toByte()
            buf[1] = subIndex.toByte()
            buf[2] = cmdIndex.toByte()
            buf[3] = level.toByte()
            putU16LE(buf, 4, option)
        }

    /** FF03 layout B WINNER: same header, wandId written at offset 9-10 (u16). */
    private fun buildWinnerPayload(subIndex: Int, winnerWandId: Int): ByteArray =
        ByteArray(20).also { buf ->
            buf[0] = MSG_TYPE_GAME_MODE.toByte()
            buf[1] = subIndex.toByte()
            buf[2] = CMD_WINNER.toByte()
            putU16LE(buf, 9, winnerWandId)
        }

    /**
     * FF04 result Notify (`GameMode_Spec_v2_6.docx` §2.4, unchanged): subIndex at offset 2,
     * redScore/blueScore/totalCount at 6/8/10, wandId at offset 14 per spec §7.1.
     * Returns null if bytes are too short to parse.
     */
    private fun parseResult(bytes: ByteArray): Array<Int>? {
        if (bytes.size < 12) return null
        val subIndex   = getU16LE(bytes, 2)
        val redScore   = getU16LE(bytes, 6)
        val blueScore  = getU16LE(bytes, 8)
        val totalCount = getU16LE(bytes, 10)
        val wandId     = if (bytes.size >= 16) getU16LE(bytes, 14) else 0
        return arrayOf(subIndex, redScore, blueScore, totalCount, wandId)
    }

    private operator fun Array<Int>.component1() = this[0]
    private operator fun Array<Int>.component2() = this[1]
    private operator fun Array<Int>.component3() = this[2]
    private operator fun Array<Int>.component4() = this[3]
    private operator fun Array<Int>.component5() = this[4]

    private fun putU16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun getU16LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
}
