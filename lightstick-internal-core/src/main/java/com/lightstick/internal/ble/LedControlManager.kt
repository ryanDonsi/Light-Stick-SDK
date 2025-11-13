package com.lightstick.internal.ble

import android.Manifest
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LED 제어를 위한 얇은 래퍼(Thin Layer).
 *
 * 설계 요점
 * - 바이트 단위 전송만 담당: 4B 색상, 16B 이펙트, 타임라인 재생.
 * - 실제 GATT 쓰기는 [GattClient.writeNoResponse]로 위임하며,
 *   **Coalesce**를 사용해 대기열에서 동일 종류 작업은 항상 "최신 1건"만 유지한다.
 *   - 컬러 전송  → coalesceKey = "LCS:COLOR"
 *   - 이펙트 전송 → coalesceKey = "LCS:PAYLOAD"
 * - 전송 실패(false)는 "GATT에 쓰기 시도조차 못한 상태"(미연결/UUID 미발견 등)이므로,
 *   재생(play) 도중 발생 시 **즉시 코루틴을 cancel**하여 지연 누적을 방지한다.
 *
 * 스레드/코루틴
 * - 재생은 IO 디스패처 코루틴에서 수행.
 * - 동시 재생을 막기 위해 [playMutex]로 보호하며, 호출 시 이전 재생은 cancel 후 교체.
 *
 * 권한
 * - 모든 퍼블릭 API는 BLUETOOTH_CONNECT 권한이 필요.
 * - 퍼미션/연결/UUID 검증은 하위 [GattClient]에서 수행(여기선 중복 가드 생략).
 */
internal class LedControlManager(
    private val gattClient: GattClient
) : AutoCloseable {

    /** 재생 작업용 스코프 (IO) */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 동시 재생 방지용 뮤텍스 */
    private val playMutex = Mutex()

    /** 현재 진행 중인 재생 잡 */
    @Volatile
    private var playJob: Job? = null

    // --------------------------------------------------------------------------------------------
    // 내부 공통 전송 유틸 (Coalesce 적용 지점)
    // --------------------------------------------------------------------------------------------

    /**
     * NO_RESPONSE 타입으로 전송(대기열에 enqueue). Coalesce 파라미터 전달.
     *
     * @param serviceUuid 대상 서비스 UUID
     * @param charUuid    대상 캐릭터리스틱 UUID
     * @param data        전송 데이터
     * @param coalesceKey 동일 키는 대기 작업을 최신으로 치환
     * @return 큐 투입 성공 여부(true: 정상 투입, false: 즉시 실패)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendNoResponseCoalesced(
        serviceUuid: java.util.UUID,
        charUuid: java.util.UUID,
        data: ByteArray,
        coalesceKey: String
    ): Boolean {
        return gattClient.writeNoResponse(
            service = serviceUuid,
            characteristic = charUuid,
            data = data,
            coalesceKey = coalesceKey,
            replaceIfSameKey = true
        )
    }

    // --------------------------------------------------------------------------------------------
    // 퍼블릭 API: 4B 컬러 전송
    // --------------------------------------------------------------------------------------------

    /**
     * 4바이트 색상 패킷을 전송한다.
     *
     * 포맷: [R, G, B, Transition] (각 1바이트)
     * - Transition: 펌웨어 정의(프레임/틱 단위 등)
     *
     * Coalesce 정책
     * - 같은 프레임이 연달아 들어와도 대기열에는 항상 최신 1건만 유지("LCS:COLOR").
     *
     * @throws IllegalArgumentException packet4의 크기가 4가 아닐 때
     * @return true면 큐 투입 성공(실제 완료는 GATT 콜백), false면 즉시 실패
     */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendColorPacket(packet4: ByteArray): Boolean {
        require(packet4.size == 4) { "Color packet must be 4 bytes [R,G,B,transition]" }
        return sendNoResponseCoalesced(
            serviceUuid = UuidConstants.LCS_SERVICE,
            charUuid = UuidConstants.LCS_COLOR,
            data = packet4,
            coalesceKey = "LCS:COLOR"
        )
    }

    // --------------------------------------------------------------------------------------------
    // 퍼블릭 API: 16B 이펙트 페이로드 전송
    // --------------------------------------------------------------------------------------------

    /**
     * 16바이트 이펙트 페이로드를 전송한다.
     *
     * Coalesce 정책
     * - 대기열에는 항상 최신 1건만 유지("LCS:PAYLOAD").
     *
     * @throws IllegalArgumentException bytes16의 크기가 16이 아닐 때
     * @return true면 큐 투입 성공, false면 즉시 실패
     */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendEffectPayload(bytes16: ByteArray): Boolean {
        require(bytes16.size == 16) { "Effect payload must be 16 bytes" }
        return sendNoResponseCoalesced(
            serviceUuid = UuidConstants.LCS_SERVICE,
            charUuid = UuidConstants.LCS_PAYLOAD,
            data = bytes16,
            coalesceKey = "LCS:PAYLOAD"
        )
    }

    // --------------------------------------------------------------------------------------------
    // 퍼블릭 API: 타임라인 재생
    // --------------------------------------------------------------------------------------------

    /**
     * (timestampMs, 16B frame) 리스트를 시간에 맞춰 순차 전송한다.
     *
     * 동작
     * - 입력을 timestamp 오름차순으로 정렬.
     * - 첫 항목 ts를 기준 시점으로 상대화하여, 실제 경과 시간과 맞춰 딜레이 후 전송.
     * - 각 전송은 Coalesce("LCS:PAYLOAD") 적용: 대기열엔 항상 최신 프레임 1건만 남음.
     *
     * 실패 처리
     * - 전송 false(=미연결/UUID 미발견 등) 발생 시 **즉시 cancel**하여 지연 누적을 차단.
     *
     * @throws IllegalArgumentException entries가 비어있거나, 16바이트가 아닌 프레임이 포함된 경우
     */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun play(entries: List<Pair<Long, ByteArray>>) {
        require(entries.isNotEmpty()) { "entries is empty" }
        entries.forEach { (_, frame) -> require(frame.size == 16) { "Frame must be 16 bytes" } }

        // 기존 재생 중지 후 교체
        playJob?.cancel()
        playJob = scope.launch {
            playMutex.withLock {
                val sorted = entries.sortedBy { it.first }
                val baseTs = sorted.first().first
                val start = System.nanoTime()

                for ((tsMs, frame) in sorted) {
                    // 예정 시각까지 대기
                    val dueMs = (tsMs - baseTs).coerceAtLeast(0L)
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000
                    val waitMs = (dueMs - elapsedMs).coerceAtLeast(0L)
                    if (waitMs > 0) delay(waitMs)

                    // Coalesce 전송: 실패 시 즉시 중단
                    val ok = sendNoResponseCoalesced(
                        serviceUuid = UuidConstants.LCS_SERVICE,
                        charUuid = UuidConstants.LCS_PAYLOAD,
                        data = frame,
                        coalesceKey = "LCS:PAYLOAD"
                    )
                    if (!ok) {
                        this@launch.cancel("GATT not ready or characteristic not found; stop timeline")
                        break
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------------------------------------
    // 퍼블릭 API: 재생 중지/리소스 정리
    // --------------------------------------------------------------------------------------------

    /** 진행 중인 타임라인 재생을 중단한다(안전 재호출 가능). */
    @MainThread
    fun stop() {
        playJob?.cancel()
        playJob = null
    }

    /** AutoCloseable 구현: 재생 중지 및 코루틴 스코프 취소. */
    override fun close() {
        stop()
        scope.cancel()
    }
}
