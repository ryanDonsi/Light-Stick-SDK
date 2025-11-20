package com.lightstick.internal.ble.ota

import android.Manifest
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import com.lightstick.internal.ble.GattClient
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
 * Telink 스타일 OTA(Over-the-Air) 전송 매니저.
 *
 * OTA 과정은 BLE GATT를 통해 펌웨어 데이터를 순차 전송하는 방식으로,
 * 다음의 순서를 반드시 지켜야 함.
 *
 * 순서:
 *  1) Notification 활성화 (setCharacteristicNotification(true))
 *  2) CCCD(Characteristic Configuration Descriptor) 0x0100 쓰기
 *  3) (선택) START/HEADER Opcode 전송
 *  4) Payload 데이터를 WRITE_NO_RESPONSE 방식으로 연속 전송
 *
 * 특징:
 *  - MTU 교섭 결과(MTU-3)를 반영하여 실제 전송 가능한 최대 페이로드 크기를 계산
 *  - 진행률(%)을 계산해 콜백으로 전달
 *  - abort() 호출 시 즉시 중단 가능
 *
 * 주의:
 *  - 일부 디바이스는 Notify 기반 ACK 또는 Verify 단계를 요구할 수 있음.
 *    이 경우 onCharacteristicChanged()에서 별도 검증 루틴을 구현해야 함.
 *  - 본 클래스는 단일 연결 내에서 안정적인 스트리밍 전송만 담당함.
 */
internal class OtaManager(
    private val gatt: GattClient
) : AutoCloseable {

    /** OTA 전용 코루틴 스코프 (IO 디스패처 기반, SupervisorJob 사용) */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 현재 OTA 상태 (Flow로 외부 관찰 가능) */
    private val _state = MutableStateFlow(OtaState.IDLE)
    val state: StateFlow<OtaState> get() = _state

    /** OTA 전송 잡(Job)과 abort 플래그 */
    @Volatile private var job: Job? = null
    @Volatile private var abortFlag: Boolean = false

    /** 상수 정의 */
    companion object {
        private const val DEFAULT_MTU = 23               // 기본 MTU 값 (Negotiation 실패 시)
        private const val GATT_HEADER_LEN = 3            // ATT 헤더 크기
        private const val MIN_PAYLOAD = 20               // 최소 페이로드 크기
    }

    /**
     * OTA 시작 함수.
     *
     * @param serviceUuid  OTA Service UUID
     * @param dataCharUuid OTA Data Characteristic UUID
     * @param firmware     전송할 펌웨어 데이터(ByteArray)
     * @param preferredMtu 우선 요청할 MTU 값 (예: 247) / 0 이하일 경우 요청 생략
     * @param startOpcodes (선택) OTA 시작 시 전송할 헤더/Opcode
     * @param onProgress   진행률 콜백 (0~100%)
     * @param onResult     완료 콜백 (성공/실패 Result)
     */
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

        // === 사전 방어 ===
        if (firmware.isEmpty()) {
            onResult(Result.failure(IllegalArgumentException("펌웨어 데이터가 비어 있습니다.")))
            return
        }

        // === OTA 수행 ===
        job = scope.launch {
            try {
                _state.value = OtaState.PREPARE
                Log.i("OTA 준비 시작 (MTU 협상 및 Notify 설정)")

                // 1) MTU 협상 (필요 시)
                val mtu = if (preferredMtu > 0) suspendMtu(preferredMtu) else DEFAULT_MTU
                Log.d("MTU 협상 결과: $mtu")

                // 2) Notification 활성화 + CCCD 설정
                suspendEnableNotify(serviceUuid, dataCharUuid)
                Log.d("Notification 활성화 및 CCCD(0x0100) 설정 완료")

                // 3) (선택) START/HEADER Opcode 전송
                if (startOpcodes?.isNotEmpty() == true) {
                    val ok = gatt.writeNoResponse(serviceUuid, dataCharUuid, startOpcodes)
                    if (!ok) error("START Opcode 전송 실패")
                    Log.d("START Opcode(${startOpcodes.size}B) 전송 완료")
                    delay(500) // 장치 전이 안정화 대기
                }

                _state.value = OtaState.TRANSFER
                Log.i("펌웨어 전송 시작 (TRANSFER 단계)")

                // 4) 펌웨어 데이터 전송
                val payloadMax = (mtu - GATT_HEADER_LEN).coerceAtLeast(MIN_PAYLOAD)
                var sent = 0
                val total = firmware.size
                var lastReported = -1

                while (sent < total) {
                    // 중단 요청 확인
                    if (abortFlag) error("사용자 중단 요청")

                    val end = min(sent + payloadMax, total)
                    val chunk = firmware.copyOfRange(sent, end)

                    val ok = gatt.writeNoResponse(serviceUuid, dataCharUuid, chunk)
                    if (!ok) error("GATT Write 실패: $sent/$total")

                    sent = end

                    // 진행률 계산 및 보고 (1% 단위)
                    val progress = ((sent.toLong() * 100L) / total.toLong()).toInt()
                    if (progress != lastReported) {
                        lastReported = progress
                        onProgress(progress)
                    }

                    // BLE 스택 안정화용 미세 지연
                    delay(10)
                }

                // 완료 처리
                if (lastReported != 100) onProgress(100)
                _state.value = OtaState.COMPLETE
                Log.i("OTA 전송 완료 (COMPLETE)")
                onResult(Result.success(Unit))
            } catch (t: Throwable) {
                // 예외 처리 (중단/오류 구분)
                _state.value = if (abortFlag) OtaState.ABORTED else OtaState.ERROR
                if (abortFlag) Log.w("OTA 중단됨: ${t.message}")
                else Log.e("OTA 오류 발생: ${t.message}", t)
                onResult(Result.failure(t))
            }
        }
    }

    /**
     * OTA 전송을 즉시 중단한다.
     * 중단 플래그를 세우고 Job을 취소함.
     */
    @MainThread
    fun abort() {
        abortFlag = true
        job?.cancel()
        job = null
        // 실제 상태 전환은 예외 처리 블록에서 수행됨
    }

    /**
     * 리소스 정리.
     * OTA 중단 후 내부 CoroutineScope를 해제함.
     */
    override fun close() {
        abort()
        _state.value = OtaState.IDLE
        scope.cancel()
        Log.d("OTA Manager 종료 및 자원 해제")
    }

    // --------------------------------------------------------------------
    // 내부 suspend 헬퍼 함수
    // --------------------------------------------------------------------

    /**
     * MTU 협상 요청 (비동기)
     * @return 협상된 MTU (실패 시 기본 23)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun suspendMtu(preferred: Int): Int =
        suspendCancellableCoroutine { cont ->
            gatt.requestMtu(preferred) { res ->
                cont.resume(res.getOrElse { DEFAULT_MTU })
            }
        }

    /**
     * Notification 활성화 및 CCCD(0x0100) 설정 (비동기)
     * 실패 시 예외 발생
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun suspendEnableNotify(service: UUID, chr: UUID): Unit =
        suspendCancellableCoroutine { cont ->
            gatt.enableNotifications(
                serviceUuid = service,
                charUuid = chr
            ) { res ->
                res.onSuccess { cont.resume(Unit) }
                    .onFailure { e -> cont.resumeWithException(e) }
            }
        }
}
