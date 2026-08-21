package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import com.lightstick.internal.util.Log
import androidx.annotation.RequiresPermission

/**
 * Game command sender and result parser for the relay/master wand game protocol
 * (`GameMode_Spec_v2_7.docx` — protocol structs unified: FF03/FF04/§7.1 all share the same
 * msgType(offset 0) single discriminator + effectIndex(offset 18-19) shape, replacing the old
 * per-struct `effectIndex=0x0005` sentinel and 16-bit fields).
 *
 * Writes 20-byte command payloads to FF03 (LCS_GAME_CMD) and subscribes to
 * FF04 (LCS_GAME_RESULT) Notify to receive game results.
 *
 * FF03 command payload (§2.3):
 *   Offset 0     msgType   (u8)      – always 1 (MSG_TYPE_GAMEMODE)
 *   Offset 1     subIndex  (u8)      – game mode 1~4
 *   Offset 2     cmdIndex  (u8)      – READY(1) / STOP(3) / CLEAR(4) / WINNER(6) /
 *                                       TEAM_ASSIGN(7) / TEAM_ASSIGN_END(9, relay-local only —
 *                                       not forwarded over 802.15.4)
 *   Offset 3     level     (u8)      – Mode1/2/3 difficulty; Mode4 TEAM_ASSIGN/END: team id
 *                                       (0=RED/1=BLUE); Mode4 READY: round count (1~3)
 *   Offset 4-5   option    (u16 LE)  – Mode3 random team (0xFF), Mode4 READY measure ms
 *   Offset 6-17  unused in this SDK (result/msgId/wandId/reserved — 0; WINNER sets wandId
 *                                     at offset 9-10)
 *   Offset 18-19 effectIndex (u16 LE) – downlink dedup/sequence only, not parsed by firmware
 *                                        for message-type discrimination (0 is fine)
 *
 * FF04 result Notify payload (§2.4 — now the *same* struct shape as FF03, unlike pre-v2.7):
 *   Offset 0     msgType     (u8)     – always 1 (MSG_TYPE_GAMEMODE)
 *   Offset 1     subIndex    (u8)     – game mode 1~4
 *   Offset 2     cmdIndex    (u8)     – RESULT(5) or TEAM_CONFIRM(8, aggregated) — field
 *                                        meaning below depends on this
 *   Offset 3-4   redScore    (u16 LE) – RESULT: Mode1/2 individual score / Mode3-4 RED team
 *                                        total. TEAM_CONFIRM: unused (0)
 *   Offset 5-6   blueScore   (u16 LE) – RESULT: Mode1/2 always 0 / Mode3-4 BLUE team total.
 *                                        TEAM_CONFIRM: unused (0)
 *   Offset 7-8   totalCount  (u16 LE) – RESULT: cumulative wand count reporting so far
 *                                        (Mode1/2). TEAM_CONFIRM: headcount confirmed for the
 *                                        team that was just ended via TEAM_ASSIGN_END
 *   Offset 9-10  wandId      (u16 LE) – RESULT: Mode1/2 reporting wand's id, Mode3-4 = 0.
 *                                        TEAM_CONFIRM: reinterpreted as team id (0=RED/1=BLUE)
 *   Offset 11-17 reserved
 *   Offset 18-19 effectIndex (u16 LE) – unused on this path (FF04 is a reliable one-shot
 *                                        Notify), always 0
 *
 * TEAM_CONFIRM(8) is only ever a *relay-aggregated* Notify from the SDK's point of view: each
 * wand's individual team-lock press goes out over 802.15.4 as its own TEAM_CONFIRM, but the
 * relay collects those silently and only forwards one aggregated TEAM_CONFIRM Notify — with the
 * confirmed headcount in `totalCount` — after the app sends TEAM_ASSIGN_END(9) for that team.
 */
internal class GameManager(private val gattClient: GattClient) {

    companion object {
        private const val MSG_TYPE_GAME_MODE = 1
        const val CMD_READY            = 1
        const val CMD_STOP             = 3
        const val CMD_CLEAR            = 4
        const val CMD_RESULT           = 5
        const val CMD_WINNER           = 6
        const val CMD_TEAM_ASSIGN      = 7
        const val CMD_TEAM_CONFIRM     = 8
        const val CMD_TEAM_ASSIGN_END  = 9

        private const val SUB_INDEX_TEAM_SIMULTANEOUS = 4

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
    fun subscribeResults(
        onResult: (subIndex: Int, cmdIndex: Int, redScore: Int, blueScore: Int, totalCount: Int, wandId: Int) -> Unit
    ) {
        gattClient.addNotificationListener(UuidConstants.LCS_GAME_RESULT) { bytes ->
            Log.d("[GameManager] FF04 RX [${bytes.size}B] raw : ${bytes.toHex()}")

            val parsed = parseResult(bytes)
            if (parsed == null) {
                Log.w("[GameManager] FF04 RX parse failed (size=${bytes.size}, need >=11)")
                return@addNotificationListener
            }
            val (si, ci, r, b, t, w) = parsed
            Log.d(
                "[GameManager] FF04 RX parsed : subIndex=$si cmdIndex=$ci redScore=$r blueScore=$b totalCount=$t wandId=0x%04X"
                    .format(w)
            )
            if (si !in 1..4) Log.w("[GameManager] FF04 RX unexpected subIndex=$si (expected 1~4)")
            if (ci != CMD_RESULT && ci != CMD_TEAM_CONFIRM) {
                Log.w("[GameManager] FF04 RX unexpected cmdIndex=$ci (expected RESULT=5 or TEAM_CONFIRM=8)")
            }
            if (ci == CMD_RESULT && (w == 0x0000 || w == 0xFFFF)) {
                Log.w("[GameManager] FF04 RX wandId=0x%04X is invalid sentinel".format(w))
            }
            onResult(si, ci, r, b, t, w)
        }
        gattClient.setCharacteristicNotification(
            serviceUuid = UuidConstants.LCS_SERVICE,
            charUuid    = UuidConstants.LCS_GAME_RESULT,
            enable      = true,
            onResult    = { result ->
                result.onSuccess { Log.d("[GameManager] FF04 CCCD subscribe OK") }
                result.onFailure { Log.w("[GameManager] FF04 CCCD subscribe FAILED: ${it.message}") }
            }
        )
    }

    fun unsubscribeResults() {
        gattClient.removeNotificationListener(UuidConstants.LCS_GAME_RESULT)
    }

    // -------------------------------------------------------------------------
    // Game commands (FF03)
    // -------------------------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendReady(subIndex: Int, level: Int, option: Int): Boolean {
        val payload = buildPayload(subIndex, CMD_READY, level, option)
        Log.d("[GameManager] FF03 TX READY subIndex=$subIndex level=$level option=0x%02X".format(option))
        Log.d("[GameManager] FF03 TX raw : ${payload.toHex()}")
        return writeGameCmd(payload)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendStop(): Boolean {
        val payload = buildPayload(0, CMD_STOP, 0, 0)
        Log.d("[GameManager] FF03 TX STOP raw : ${payload.toHex()}")
        return writeGameCmd(payload)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendClear(): Boolean {
        val payload = buildPayload(0, CMD_CLEAR, 0, 0)
        Log.d("[GameManager] FF03 TX CLEAR raw : ${payload.toHex()}")
        return writeGameCmd(payload)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendWinner(subIndex: Int, winnerWandId: Int): Boolean {
        val payload = buildWinnerPayload(subIndex, winnerWandId)
        Log.d("[GameManager] FF03 TX WINNER subIndex=$subIndex winnerWandId=0x%04X".format(winnerWandId))
        Log.d("[GameManager] FF03 TX raw : ${payload.toHex()}")
        return writeGameCmd(payload)
    }

    /**
     * Mode 4 only: starts (or continues) team assignment for [teamId] (0=RED/1=BLUE) —
     * unassigned wands blink that team's color; pressing the wand's button locks it in.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendTeamAssign(teamId: Int): Boolean {
        val payload = buildPayload(SUB_INDEX_TEAM_SIMULTANEOUS, CMD_TEAM_ASSIGN, teamId, 0)
        Log.d("[GameManager] FF03 TX TEAM_ASSIGN teamId=$teamId")
        Log.d("[GameManager] FF03 TX raw : ${payload.toHex()}")
        return writeGameCmd(payload)
    }

    /**
     * Mode 4 only: ends assignment for [teamId] (0=RED/1=BLUE). Not forwarded to wands over
     * 802.15.4 — the relay handles it locally and replies with an aggregated TEAM_CONFIRM(8)
     * Notify on FF04 carrying that team's confirmed headcount.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendTeamAssignEnd(teamId: Int): Boolean {
        val payload = buildPayload(SUB_INDEX_TEAM_SIMULTANEOUS, CMD_TEAM_ASSIGN_END, teamId, 0)
        Log.d("[GameManager] FF03 TX TEAM_ASSIGN_END teamId=$teamId")
        Log.d("[GameManager] FF03 TX raw : ${payload.toHex()}")
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

    /** FF03: msgType(0)/subIndex(1)/cmdIndex(2)/level(3)/option(4-5 u16). */
    private fun buildPayload(subIndex: Int, cmdIndex: Int, level: Int, option: Int): ByteArray =
        ByteArray(20).also { buf ->
            buf[0] = MSG_TYPE_GAME_MODE.toByte()
            buf[1] = subIndex.toByte()
            buf[2] = cmdIndex.toByte()
            buf[3] = level.toByte()
            putU16LE(buf, 4, option)
        }

    /** FF03 WINNER: same header, wandId written at offset 9-10 (u16). */
    private fun buildWinnerPayload(subIndex: Int, winnerWandId: Int): ByteArray =
        ByteArray(20).also { buf ->
            buf[0] = MSG_TYPE_GAME_MODE.toByte()
            buf[1] = subIndex.toByte()
            buf[2] = CMD_WINNER.toByte()
            putU16LE(buf, 9, winnerWandId)
        }

    private data class ResultFields(
        val subIndex: Int,
        val cmdIndex: Int,
        val redScore: Int,
        val blueScore: Int,
        val totalCount: Int,
        val wandId: Int
    )

    /**
     * FF04 (§2.4, v2.7 unified struct): subIndex@1, cmdIndex@2, redScore@3-4, blueScore@5-6,
     * totalCount@7-8, wandId@9-10. Field meaning for totalCount/wandId depends on cmdIndex
     * (RESULT vs TEAM_CONFIRM) — see class doc. Returns null if bytes are too short to parse.
     */
    private fun parseResult(bytes: ByteArray): ResultFields? {
        if (bytes.size < 11) return null
        val subIndex   = bytes[1].toInt() and 0xFF
        val cmdIndex   = bytes[2].toInt() and 0xFF
        val redScore   = getU16LE(bytes, 3)
        val blueScore  = getU16LE(bytes, 5)
        val totalCount = getU16LE(bytes, 7)
        val wandId     = getU16LE(bytes, 9)
        return ResultFields(subIndex, cmdIndex, redScore, blueScore, totalCount, wandId)
    }

    private fun putU16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun getU16LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
}
