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
import kotlin.math.min
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Telink OTA 전송 매니저.
 *
 * PDU 포맷 (Telink 규격):
 *   [idx_lo][idx_hi][data...][crc16_lo][crc16_hi]
 *   CRC16 범위: index 2바이트 + data N바이트 (ARC/0xA001, init=0xFFFF)
 *
 * 디바이스 OTA FAIL 코드:
 *   1 = OTA_SUCCESS (정상 종료)
 *   2 = OTA_DATA_CRC_ERR  ← PDU 포맷 누락 시 발생
 *   3 = OTA_WRITE_FLASH_ERR
 */
internal class OtaManager(
    private val gatt: GattClient
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(OtaState.IDLE)
    val state: StateFlow<OtaState> get() = _state

    @Volatile private var job: Job? = null
    @Volatile private var abortFlag: Boolean = false

    companion object {
        private const val DEFAULT_MTU = 23
        private const val GATT_HEADER_LEN = 3

        // Telink PDU 오버헤드: 2바이트 index + 2바이트 CRC16
        private const val TELINK_PDU_OVERHEAD = 4

        // 데이터 최소값 (PDU 오버헤드 제외 후 순수 펌웨어 바이트)
        private const val MIN_DATA_BYTES = 16

        // 진행률 로그 간격 (%)
        private const val PROGRESS_LOG_INTERVAL = 10
    }

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
        job?.cancel()

        if (firmware.isEmpty()) {
            onResult(Result.failure(IllegalArgumentException("펌웨어 데이터가 비어 있습니다.")))
            return
        }

        job = scope.launch {
            try {
                _state.value = OtaState.PREPARE
                Log.i("[OTA] 준비 시작 | firmware=${firmware.size}B preferredMtu=$preferredMtu")

                // 1) MTU 협상
                val negotiatedMtu: Int
                if (preferredMtu > 0) {
                    Log.d("[OTA] MTU 협상 요청: $preferredMtu")
                    negotiatedMtu = suspendMtu(preferredMtu)
                    if (negotiatedMtu < preferredMtu) {
                        Log.w("[OTA] MTU 협상 결과 부족 — 요청=$preferredMtu 실제=$negotiatedMtu. " +
                            "패킷 수가 늘어 전송 시간이 길어질 수 있음.")
                    } else {
                        Log.d("[OTA] MTU 협상 완료: $negotiatedMtu")
                    }
                } else {
                    negotiatedMtu = DEFAULT_MTU
                    Log.d("[OTA] MTU 협상 생략 — 기본값 $DEFAULT_MTU 사용")
                }

                // ATT 페이로드에서 Telink 오버헤드(4B) 제거 후 순수 데이터 크기
                val dataMax = (negotiatedMtu - GATT_HEADER_LEN - TELINK_PDU_OVERHEAD).coerceAtLeast(MIN_DATA_BYTES)
                val totalPackets = (firmware.size + dataMax - 1) / dataMax
                Log.d("[OTA] PDU 레이아웃 — ATT페이로드=${dataMax + TELINK_PDU_OVERHEAD}B " +
                    "(data=${dataMax}B + index=2B + crc16=2B) | 예상 패킷 수=$totalPackets")

                // 2) Notification 활성화
                Log.d("[OTA] Notification 활성화 요청: charUuid=$dataCharUuid")
                suspendEnableNotify(serviceUuid, dataCharUuid)
                Log.d("[OTA] Notification 활성화 완료")

                // 3) START Opcode 전송
                if (startOpcodes?.isNotEmpty() == true) {
                    Log.d("[OTA] START Opcode 전송: ${startOpcodes.toHex()}")
                    val ok = gatt.writeCharacteristic(
                        serviceUuid = serviceUuid,
                        charUuid = dataCharUuid,
                        data = startOpcodes,
                        writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    )
                    if (!ok) error("[OTA] START Opcode 전송 실패 — GATT write 거부됨")
                    Log.d("[OTA] START Opcode 전송 완료. 디바이스 준비 대기 500ms")
                    delay(500)
                } else {
                    Log.d("[OTA] START Opcode 없음 — 즉시 데이터 전송 시작")
                }

                _state.value = OtaState.TRANSFER
                Log.i("[OTA] 펌웨어 전송 시작")

                // 4) 펌웨어 데이터 전송 (Telink PDU 포맷)
                var sent = 0
                val total = firmware.size
                var lastReported = -1
                var lastLoggedProgress = -1
                var pktIndex = 0

                while (sent < total) {
                    if (abortFlag) error("사용자 중단 요청")

                    val end = min(sent + dataMax, total)
                    val data = firmware.copyOfRange(sent, end)

                    // Telink PDU: [idx_lo][idx_hi][data...][crc16_lo][crc16_hi]
                    val indexBytes = byteArrayOf(
                        (pktIndex and 0xFF).toByte(),
                        ((pktIndex shr 8) and 0xFF).toByte()
                    )
                    val crc = Crc16.of(indexBytes, data)
                    val pdu = indexBytes + data + byteArrayOf(
                        (crc and 0xFF).toByte(),
                        ((crc shr 8) and 0xFF).toByte()
                    )

                    val ok = gatt.writeCharacteristic(
                        serviceUuid = serviceUuid,
                        charUuid = dataCharUuid,
                        data = pdu,
                        writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    )

                    if (!ok) {
                        Log.e("[OTA] GATT Write 실패 — pkt=$pktIndex offset=$sent/$total. " +
                            "연결이 끊겼거나 캐릭터리스틱을 찾을 수 없음.")
                        error("[OTA] GATT Write 실패: pkt=$pktIndex offset=$sent/$total")
                    }

                    sent = end
                    pktIndex++

                    val progress = ((sent.toLong() * 100L) / total.toLong()).toInt()
                    if (progress != lastReported) {
                        lastReported = progress
                        onProgress(progress)
                    }

                    // PROGRESS_LOG_INTERVAL % 단위로 진행 상황 로그
                    val logBucket = (progress / PROGRESS_LOG_INTERVAL) * PROGRESS_LOG_INTERVAL
                    if (logBucket > lastLoggedProgress) {
                        lastLoggedProgress = logBucket
                        Log.i("[OTA] 전송 중: $progress% ($sent/$total B, pkt=$pktIndex/$totalPackets)")
                    }

                    delay(10)
                }

                if (lastReported != 100) onProgress(100)
                _state.value = OtaState.COMPLETE
                Log.i("[OTA] 전송 완료 — 총 $pktIndex 패킷, ${firmware.size}B")
                onResult(Result.success(Unit))

            } catch (t: Throwable) {
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
    // Suspend Helpers
    // ============================================================================================

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
