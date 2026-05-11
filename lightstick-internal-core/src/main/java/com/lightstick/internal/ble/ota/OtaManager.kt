package com.lightstick.internal.ble.ota

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import com.lightstick.internal.ble.GattClient
import com.lightstick.internal.util.Crc16
import com.lightstick.internal.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Telink OTA 전송 매니저 (Legacy Protocol 기준).
 *
 * ── Telink OTA 정규 플로우 (OtaController.java 참조) ──────────────────────
 *  1. MTU 협상
 *  2. Notification 활성화
 *  3. START opcode 전송  [0x01, 0xFF]
 *  4. OTA characteristic READ (디바이스가 수신 준비 완료 신호)
 *  5. 펌웨어 PDU 전송 (1패킷씩, ACK 대기 후 다음 패킷)
 *  6. 마지막 패킷 전송 후 END command 전송
 *  7. Legacy: END write 완료 → 성공
 *     Extended: CMD_OTA_RESULT(0xFF06) 통지 대기
 *
 * ── Telink PDU 포맷 ──────────────────────────────────────────────────────
 *  [idx_lo][idx_hi][data 0..realPduLen-1][crc16_lo][crc16_hi]
 *  CRC16-ARC: poly=0xA001, init=0xFFFF, 커버 범위 = index(2B) + data
 *  realPduLen = (MTU - 7) / 16 * 16  ← 반드시 16의 배수
 *  마지막 패킷: 0xFF로 패딩하여 realPduLen 맞춤
 *
 * ── 디바이스 OTA FAIL 코드 ───────────────────────────────────────────────
 *  1 = OTA_SUCCESS
 *  2 = OTA_DATA_CRC_ERR  ← PDU 포맷 위반 또는 CRC 불일치
 *  3 = OTA_WRITE_FLASH_ERR
 *
 * ── 큐 플러딩 방지 ───────────────────────────────────────────────────────
 *  writeCharacteristicAndWait 사용으로 각 패킷 write ACK 후 다음 패킷 전송.
 *  기존 delay(10) 루프 방식은 큐(max=64, DROP_OLDEST) 오버플로우를 유발함.
 */
internal class OtaManager(
    private val gatt: GattClient
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(OtaState.IDLE)
    val state: StateFlow<OtaState> get() = _state

    @Volatile private var job: Job? = null
    @Volatile private var abortFlag: Boolean = false

    // 디바이스에서 OTA RESULT 통지로 전달된 오류 (0xFF06 notification)
    @Volatile private var deviceResultError: Throwable? = null

    companion object {
        private const val DEFAULT_MTU = 23

        // ATT Write Without Response 오버헤드: 1B opcode + 2B handle = 3B
        private const val ATT_OVERHEAD = 3
        // Telink PDU 오버헤드: 2B index + 2B CRC16 = 4B
        private const val TELINK_OVERHEAD = 4
        // PDU 데이터 최소값 (MTU=23 기준: 23-7=16)
        private const val MIN_PDU_DATA = 16

        // START_READ 이후 디바이스 준비 대기 (Telink 참조: START_WAITING = 200ms)
        private const val START_WAITING_MS = 200L

        private const val PROGRESS_LOG_INTERVAL = 10

        private val OPCODE_START        = OtaOpcode.CMD_OTA_START.toBytes()
        private val OPCODE_END          = OtaOpcode.CMD_OTA_END.toBytes()
        private val OPCODE_RESULT_VALUE = OtaOpcode.CMD_OTA_RESULT.code.toInt()

        private fun resultCodeName(code: Int) = OtaResultCode.from(code).toString()
    }

    // ============================================================================================
    // Public API
    // ============================================================================================

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun start(
        serviceUuid: UUID,
        dataCharUuid: UUID,
        firmware: ByteArray,
        preferredMtu: Int = 0,
        startOpcodes: ByteArray? = null,
        onProgress: (Int) -> Unit,
        onResult: (Result<Unit>) -> Unit
    ) {
        abortFlag = false
        deviceResultError = null
        job?.cancel()

        if (firmware.isEmpty()) {
            onResult(Result.failure(IllegalArgumentException("펌웨어 데이터가 비어 있습니다.")))
            return
        }

        job = scope.launch {
            try {
                _state.value = OtaState.PREPARE
                Log.i("[OTA] 준비 시작 | firmware=${firmware.size}B preferredMtu=$preferredMtu")

                // ── 1. MTU 협상 ────────────────────────────────────────────────────────────────
                val negotiatedMtu: Int = if (preferredMtu > 0) {
                    Log.d("[OTA] MTU 협상 요청: $preferredMtu")
                    suspendMtu(preferredMtu).also { actual ->
                        if (actual < preferredMtu)
                            Log.w("[OTA] MTU 부족 — 요청=$preferredMtu 실제=$actual. 패킷 수 증가.")
                        else
                            Log.d("[OTA] MTU 협상 완료: $actual")
                    }
                } else {
                    Log.d("[OTA] MTU 협상 생략 — 기본값 $DEFAULT_MTU 사용")
                    DEFAULT_MTU
                }

                // realPduLen: Telink 규격 — (MTU-7) / 16 * 16, 최소 MIN_PDU_DATA
                // MTU-7 = ATT(3) + Telink PDU overhead(4) 제거 후 순수 데이터
                val rawDataLen = negotiatedMtu - ATT_OVERHEAD - TELINK_OVERHEAD
                val realPduLen = ((rawDataLen.coerceAtLeast(MIN_PDU_DATA)) / 16) * 16
                val totalPackets = (firmware.size + realPduLen - 1) / realPduLen
                Log.d("[OTA] realPduLen=$realPduLen (raw=$rawDataLen → 16배수 정렬) | " +
                    "ATT페이로드=${realPduLen + TELINK_OVERHEAD}B | 예상패킷=$totalPackets")

                // ── 2. Notification 활성화 + RESULT 통지 리스너 등록 ─────────────────────────
                Log.d("[OTA] Notification 활성화 요청: charUuid=$dataCharUuid")
                suspendEnableNotify(serviceUuid, dataCharUuid)
                Log.d("[OTA] Notification 활성화 완료")

                // 디바이스가 OTA RESULT(0xFF06)를 통지하면 오류 여부 저장
                gatt.addNotificationListener(dataCharUuid) { data ->
                    handleResultNotification(data)
                }

                // ── 3. START opcode 전송 ────────────────────────────────────────────────────
                val otaStartCmd = startOpcodes?.takeIf { it.isNotEmpty() } ?: OPCODE_START
                Log.d("[OTA] START opcode 전송: ${otaStartCmd.toHex()}")
                suspendWrite(serviceUuid, dataCharUuid, otaStartCmd)
                Log.d("[OTA] START opcode 완료")

                // ── 4. START_READ (디바이스 수신 준비 확인) ────────────────────────────────
                Log.d("[OTA] START_READ 전송 (디바이스 OTA 수신 준비 대기)")
                suspendRead(serviceUuid, dataCharUuid)
                Log.d("[OTA] START_READ 완료. ${START_WAITING_MS}ms 대기 후 전송 시작")
                delay(START_WAITING_MS)

                // ── 5. 펌웨어 데이터 전송 (1패킷 ACK 후 다음 패킷) ─────────────────────────
                _state.value = OtaState.TRANSFER
                Log.i("[OTA] 펌웨어 전송 시작")

                var sent = 0
                val total = firmware.size
                var lastReported = -1
                var lastLoggedProgress = -1
                var pktIndex = 0

                while (sent < total) {
                    if (abortFlag) error("사용자 중단 요청")
                    deviceResultError?.let { throw it }

                    val end = minOf(sent + realPduLen, total)
                    val rawData = firmware.copyOfRange(sent, end)

                    // 마지막 패킷: roundUp16(remaining)까지 0xFF 패딩 (플래시 16B 정렬)
                    // OtaPacketParser.getPacket(): dataSize = ((packetSize/16)+1)*16
                    // 전체를 realPduLen으로 패딩하면 안 됨 — last PDU 크기 달라짐
                    val data = if (rawData.size < realPduLen) {
                        val paddedSize = ((rawData.size + 15) / 16) * 16
                        ByteArray(paddedSize) { 0xFF.toByte() }.also { rawData.copyInto(it) }
                    } else rawData

                    val isLastPacket = (end == total)
                    val pdu = buildPdu(pktIndex, data)
                    if (isLastPacket) {
                        Log.d("[OTA] 마지막 패킷 — idx=$pktIndex rawSize=${rawData.size}B " +
                            "paddedSize=${data.size}B pduSize=${pdu.size}B")
                    }
                    suspendWrite(serviceUuid, dataCharUuid, pdu)

                    sent = end
                    pktIndex++

                    val progress = ((sent.toLong() * 100L) / total.toLong()).toInt()
                    if (progress != lastReported) {
                        lastReported = progress
                        onProgress(progress)
                    }
                    val logBucket = (progress / PROGRESS_LOG_INTERVAL) * PROGRESS_LOG_INTERVAL
                    if (logBucket > lastLoggedProgress) {
                        lastLoggedProgress = logBucket
                        Log.i("[OTA] 전송 중: $progress% ($sent/$total B, pkt=$pktIndex/$totalPackets)")
                    }
                }

                // ── 6. END command 전송 ────────────────────────────────────────────────────
                // OtaPacketParser.getIndex() = 마지막 패킷의 0-based index = pktIndex - 1
                val lastPktIdx = pktIndex - 1
                val endCmd = buildEndCommand(lastPktIdx)
                Log.d("[OTA] END command 전송: lastPktIdx=$lastPktIdx data=${endCmd.toHex()}")
                suspendWrite(serviceUuid, dataCharUuid, endCmd)
                Log.d("[OTA] END command 완료")

                // 디바이스 오류 최종 확인
                deviceResultError?.let { throw it }

                if (lastReported != 100) onProgress(100)
                _state.value = OtaState.COMPLETE
                Log.i("[OTA] 전송 완료 — 총 $pktIndex 패킷, ${firmware.size}B")
                onResult(Result.success(Unit))

            } catch (t: Throwable) {
                gatt.removeNotificationListener(dataCharUuid)
                _state.value = if (abortFlag) OtaState.ABORTED else OtaState.ERROR
                if (abortFlag) Log.w("[OTA] 중단됨: ${t.message}")
                else Log.e("[OTA] 오류 발생: ${t.message}", t)
                onResult(Result.failure(t))
            }
        }
    }

    fun abort() {
        Log.i("[OTA] 중단 요청")
        abortFlag = true
        job?.cancel()
    }

    override fun close() {
        abort()
        scope.cancel()
    }

    // ============================================================================================
    // PDU / Command Builders
    // ============================================================================================

    /**
     * Telink OTA PDU: [idx_lo][idx_hi][data...][crc16_lo][crc16_hi]
     * CRC16 범위: index 2바이트 + data N바이트
     */
    private fun buildPdu(pktIndex: Int, data: ByteArray): ByteArray {
        val idx = byteArrayOf(
            (pktIndex and 0xFF).toByte(),
            ((pktIndex shr 8) and 0xFF).toByte()
        )
        val crc = Crc16.of(idx, data)
        return idx + data + byteArrayOf(
            (crc and 0xFF).toByte(),
            ((crc shr 8) and 0xFF).toByte()
        )
    }

    /**
     * Telink OTA END command:
     * [opcode_lo][opcode_hi][idx_lo][idx_hi][~idx_lo][~idx_hi][0x00 x14]
     * 총 20바이트
     */
    private fun buildEndCommand(pktCount: Int): ByteArray {
        val payload = ByteArray(18)
        payload[0] = (pktCount and 0xFF).toByte()
        payload[1] = ((pktCount shr 8) and 0xFF).toByte()
        payload[2] = (pktCount.inv() and 0xFF).toByte()
        payload[3] = ((pktCount.inv() shr 8) and 0xFF).toByte()
        // payload[4..17] = 0x00 (reserved, ByteArray 기본값)
        return OPCODE_END + payload
    }

    // ============================================================================================
    // Notification Handler
    // ============================================================================================

    private fun handleResultNotification(data: ByteArray) {
        if (data.size < 3) return
        val opcode = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        if (opcode != OPCODE_RESULT_VALUE) return

        val resultCode = OtaResultCode.from(data[2].toInt() and 0xFF)
        Log.d("[OTA] RESULT 통지 수신: $resultCode")

        // Legacy: success는 END command write 완료로 판정 (OtaController 참조).
        // RESULT 통지는 실패 감지 전용으로 사용.
        if (resultCode != OtaResultCode.OTA_SUCCESS) {
            deviceResultError = RuntimeException("[OTA] 디바이스 OTA 실패: $resultCode")
            Log.e("[OTA] 디바이스 OTA 실패 — $resultCode. 다음 write 시 중단됩니다.")
        }
    }

    // ============================================================================================
    // Suspend Helpers
    // ============================================================================================

    /** 각 OTA PDU/command를 전송하고 onCharacteristicWrite ACK를 기다린다. */
    private suspend fun suspendWrite(
        serviceUuid: UUID,
        charUuid: UUID,
        data: ByteArray
    ) = suspendCancellableCoroutine<Unit> { cont ->
        try {
            gatt.writeCharacteristicAndWait(
                serviceUuid = serviceUuid,
                charUuid = charUuid,
                data = data,
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                onResult = { result ->
                    result.fold(
                        onSuccess = { cont.resume(Unit) },
                        onFailure = { cont.resumeWithException(it) }
                    )
                }
            )
        } catch (e: SecurityException) {
            cont.resumeWithException(SecurityException("Permission denied for write", e))
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    /** START_READ: START opcode 전송 후 characteristic을 읽어 디바이스 준비 확인. */
    private suspend fun suspendRead(
        serviceUuid: UUID,
        charUuid: UUID
    ) = suspendCancellableCoroutine<Unit> { cont ->
        try {
            gatt.readCharacteristic(
                serviceUuid = serviceUuid,
                charUuid = charUuid,
                onResult = { result ->
                    result.fold(
                        onSuccess = { cont.resume(Unit) },
                        onFailure = { t ->
                            Log.w("[OTA] START_READ 실패 (무시): ${t.message}")
                            cont.resume(Unit) // read 실패는 치명적이지 않음
                        }
                    )
                }
            )
        } catch (e: SecurityException) {
            cont.resumeWithException(SecurityException("Permission denied for read", e))
        } catch (e: Exception) {
            Log.w("[OTA] START_READ 예외 (무시): ${e.message}")
            cont.resume(Unit)
        }
    }

    private suspend fun suspendMtu(mtu: Int): Int = suspendCancellableCoroutine { cont ->
        try {
            gatt.requestMtu(mtu) { result ->
                result.fold(
                    onSuccess = { negotiated -> cont.resume(negotiated) },
                    onFailure = { t -> cont.resumeWithException(t) }
                )
            }
        } catch (e: SecurityException) {
            cont.resumeWithException(SecurityException("Permission denied for requestMtu", e))
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    private suspend fun suspendEnableNotify(
        serviceUuid: UUID,
        charUuid: UUID
    ) = suspendCancellableCoroutine<Unit> { cont ->
        try {
            gatt.setCharacteristicNotification(
                serviceUuid = serviceUuid,
                charUuid = charUuid,
                enable = true,
                onResult = { result ->
                    result.fold(
                        onSuccess = { cont.resume(Unit) },
                        onFailure = { t -> cont.resumeWithException(t) }
                    )
                }
            )
        } catch (e: SecurityException) {
            cont.resumeWithException(SecurityException("Permission denied for setCharacteristicNotification", e))
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    // ============================================================================================
    // Util
    // ============================================================================================

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }
}
