package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LED 제어 및 타임라인 재생을 위한 매니저
 *
 * ✅ 주요 기능:
 * - 타임라인 기반 이펙트 재생 (나노초 정확도)
 * - 음악 재생 위치 동기화
 * - Effect 전송 ON/OFF 제어
 * - effectIndex 자동 관리 (사용자 투명)
 * - Seek 자동 감지
 */
internal class LedControlManager(
    private val gattClient: GattClient
) : AutoCloseable {

    companion object {
        private const val TAG = "LedControlManager"

        // LSEffectPayload byte[0] (protocol v2.0): msgType, the sole message-type discriminator.
        // Effect/timeline sends through this manager are always "Effect" — group messages
        // bypass this class entirely (see GroupControlManager) so a GroupSetup frame's msgType
        // (GROUP_SETUP) survives unmodified, and group-targeted control frames (msgType=EFFECT
        // + groupMask) skip this manager's stopTimeline()/effectIndex auto-management.
        private const val MSG_TYPE_BYTE_POSITION = 0
        const val MSG_TYPE_EFFECT = 0

        // LSEffectPayload byte[18-19] (protocol v3, u16 LE): effectIndex — pure dedup/sequence
        // number, uninvolved in message-type discrimination (that moved to byte[0] in v3).
        private const val EFFECT_INDEX_BYTE_POSITION = 18
        private const val MONITOR_INTERVAL_MS = 10L      // 내부 보간 루프 간격
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playMutex = Mutex()

    // =========== Timeline 재생 상태 ===========
    @Volatile private var timeline: List<Pair<Long, ByteArray>> = emptyList()
    @Volatile private var lastProcessedPositionMs: Long = -1
    @Volatile private var lastSentIndex: Int = -1

    // =========== 보간을 위한 기준점 ===========
    @Volatile private var anchorPositionMs: Long = 0     // 마지막으로 보고된 음악 위치
    @Volatile private var anchorSystemMs: Long = 0       // 보고 당시 시스템 시각

    // =========== Effect 전송 제어 ===========
    @Volatile private var isEffectTransmissionEnabled: Boolean = true
    @Volatile private var currentEffectIndex: Int = 1

    // =========== 재생 Job ===========
    @Volatile private var monitorJob: Job? = null
    @Volatile private var playJob: Job? = null  // 기존 play() 용

    // ============================================================================================
    // 공통 전송 유틸
    // ============================================================================================

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendNoResponseCoalesced(
        serviceUuid: java.util.UUID,
        charUuid: java.util.UUID,
        data: ByteArray,
        coalesceKey: String
    ): Boolean {
        return gattClient.writeCharacteristic(
            serviceUuid = serviceUuid,
            charUuid = charUuid,
            data = data,
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            replaceIfSameKey = true,
            coalesceKey = coalesceKey
        )
    }

    // Timeline frames must NOT coalesce — each frame is a unique ordered event
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendTimelineFrame(
        serviceUuid: java.util.UUID,
        charUuid: java.util.UUID,
        data: ByteArray
    ): Boolean {
        return gattClient.writeCharacteristic(
            serviceUuid = serviceUuid,
            charUuid = charUuid,
            data = data,
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            replaceIfSameKey = false,
            coalesceKey = null
        )
    }

    // ============================================================================================
    // 기존 API (하위 호환성)
    // ============================================================================================

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

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendEffectPayload(bytes20: ByteArray): Boolean {
        require(bytes20.size == 20) { "Effect payload must be 20 bytes" }
        stopTimeline()  // 타임라인 재생 중단
        return sendNoResponseCoalesced(
            serviceUuid = UuidConstants.LCS_SERVICE,
            charUuid = UuidConstants.LCS_PAYLOAD,
            data = setMsgType(bytes20, MSG_TYPE_EFFECT),
            coalesceKey = "LCS:PAYLOAD"
        )
    }

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun play(entries: List<Pair<Long, ByteArray>>) {
        require(entries.isNotEmpty()) { "entries is empty" }
        entries.forEach { (_, frame) -> require(frame.size == 20) { "Frame must be 20 bytes" } }

        playJob?.cancel()
        playJob = scope.launch {
            playMutex.withLock {
                val sorted = entries.sortedBy { it.first }
                val baseTs = sorted.first().first
                val start = System.nanoTime()

                for ((tsMs, frame) in sorted) {
                    val dueMs = (tsMs - baseTs).coerceAtLeast(0L)
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000
                    val waitMs = (dueMs - elapsedMs).coerceAtLeast(0L)
                    if (waitMs > 0) delay(waitMs)

                    val ok = sendNoResponseCoalesced(
                        serviceUuid = UuidConstants.LCS_SERVICE,
                        charUuid = UuidConstants.LCS_PAYLOAD,
                        data = setMsgType(frame, MSG_TYPE_EFFECT),
                        coalesceKey = "LCS:PAYLOAD"
                    )
                    if (!ok) {
                        this@launch.cancel("GATT not ready")
                        break
                    }
                }
            }
        }
    }

    @MainThread
    fun stop() {
        playJob?.cancel()
        playJob = null
    }

    // ============================================================================================
    // ✅ 새로운 API: 타임라인 기반 재생 + 음악 동기화
    // ============================================================================================

    /**
     * EFX 타임라인을 로드합니다.
     *
     * 로드와 동시에:
     * 1. 모든 프레임의 msgType을 MSG_TYPE_EFFECT(0)으로 설정 (그룹/게임 msgType과 충돌 방지)
     * 2. effectIndex가 자동으로 증가 (새로운 재생 세션 시작)
     *
     * @param frames 타임라인 엔트리 리스트 (timestampMs, 20B payload)
     */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun loadTimeline(frames: List<Pair<Long, ByteArray>>) {
        require(frames.all { it.second.size == 20 }) { "All frames must be 20 bytes" }

        stopTimeline()

        val sortedFrames = frames.sortedBy { it.first }

        // 모든 프레임을 MSG_TYPE_EFFECT(0)으로 고정 (그룹/게임 msgType 충돌 방지)
        timeline = sortedFrames.map { (timestamp, frame) ->
            timestamp to setMsgType(frame, MSG_TYPE_EFFECT)
        }

        lastSentIndex = -1
        anchorPositionMs = 0
        anchorSystemMs = SystemClock.elapsedRealtime()
        lastProcessedPositionMs = -1
        isEffectTransmissionEnabled = true

        // ✅ 새 타임라인 로드 시 effectIndex 자동 증가
        currentEffectIndex = (currentEffectIndex % 0xFFFF) + 1

        Log.d(TAG, "Timeline loaded: ${timeline.size} frames, msgType=MSG_TYPE_EFFECT, effectIndex=$currentEffectIndex")

        startMonitor()
    }

    /**
     * 현재 음악 재생 위치를 업데이트합니다.
     *
     * 내부 보간 루프가 10ms 간격으로 실제 프레임 dispatch를 처리하므로,
     * 이 메서드는 호출 간격(50~200ms)에 관계없이 정밀한 타이밍을 보장합니다.
     *
     * @param currentPositionMs 현재 음악 재생 위치 (밀리초)
     */
    @MainThread
    fun updatePlaybackPosition(currentPositionMs: Long) {
        if (timeline.isEmpty()) return

        val now = SystemClock.elapsedRealtime()

        // ✅ Seek 감지 (뒤로 1초 이상)
        if (currentPositionMs < lastProcessedPositionMs - 1000) {
            lastSentIndex = timeline.indexOfLast { it.first <= currentPositionMs }
        }

        // ✅ Seek 감지 (앞으로 10초 이상)
        if (currentPositionMs > lastProcessedPositionMs + 10000) {
            lastSentIndex = timeline.indexOfLast { it.first <= currentPositionMs }
        }

        lastProcessedPositionMs = currentPositionMs

        // 보간 기준점 갱신
        anchorPositionMs = currentPositionMs
        anchorSystemMs = now
    }

    /**
     * 보간된 현재 재생 위치를 계산합니다.
     * 마지막 보고 이후 경과한 시스템 시간을 더해 연속적인 위치를 추정합니다.
     */
    private fun interpolatedPositionMs(): Long {
        val elapsed = SystemClock.elapsedRealtime() - anchorSystemMs
        return anchorPositionMs + elapsed
    }

    /**
     * 내부 10ms 루프: 보간된 위치 기준으로 프레임 dispatch
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startMonitor() {
        monitorJob?.cancel()
        // CmdQueueManager.enqueue는 main thread에서만 호출해야 하므로 Dispatchers.Main 사용
        // delay()는 main thread에서도 non-blocking으로 동작함
        monitorJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(MONITOR_INTERVAL_MS)
                dispatchFrames(interpolatedPositionMs())
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun dispatchFrames(currentPositionMs: Long) {
        if (timeline.isEmpty()) return

        // ✅ OFF 상태면 인덱스만 업데이트, 전송 스킵
        if (!isEffectTransmissionEnabled) {
            while (lastSentIndex + 1 < timeline.size) {
                val (timestamp, _) = timeline[lastSentIndex + 1]
                if (timestamp > currentPositionMs) break
                lastSentIndex++
            }
            return
        }

        // ✅ ON 상태: 이펙트 전송
        var transmittedCount = 0
        while (lastSentIndex + 1 < timeline.size) {
            val (timestamp, frame) = timeline[lastSentIndex + 1]
            if (timestamp > currentPositionMs) break

            lastSentIndex++

            try {
                val frameWithIndex = insertEffectIndex(frame, currentEffectIndex)

                val ok = sendTimelineFrame(
                    serviceUuid = UuidConstants.LCS_SERVICE,
                    charUuid = UuidConstants.LCS_PAYLOAD,
                    data = frameWithIndex
                )

                if (ok) {
                    transmittedCount++
                } else {
                    Log.w(TAG, "Failed to send effect at ${timestamp}ms")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending effect at ${timestamp}ms: ${e.message}")
                break
            }
        }

        if (transmittedCount > 0) {
            val rangeStart = lastSentIndex - transmittedCount + 1
            val rangeEnd = lastSentIndex
            Log.d(TAG, "Transmitted $transmittedCount effects at ${currentPositionMs}ms (frames: ${rangeStart + 1}~${rangeEnd + 1}, effectIndex=$currentEffectIndex)")
        }
    }

    /**
     * 이펙트 전송을 일시정지합니다.
     *
     * 타임라인 추적은 계속되지만 BLE 전송만 중단됩니다.
     */
    @MainThread
    fun pauseEffects() {
        if (!isEffectTransmissionEnabled) return
        isEffectTransmissionEnabled = false
    }

    /**
     * 이펙트 전송을 재개합니다.
     *
     * 내부적으로 effectIndex가 자동으로 증가하여 디바이스 재동기화가 처리됩니다.
     */
    @MainThread
    fun resumeEffects() {
        if (isEffectTransmissionEnabled) return
        currentEffectIndex = (currentEffectIndex % 0xFFFF) + 1
        isEffectTransmissionEnabled = true
    }

    /**
     * 타임라인 재생을 완전히 중단합니다.
     *
     * 타임라인이 클리어되고 처음부터 다시 시작하려면
     * loadTimeline()을 다시 호출해야 합니다.
     */
    @MainThread
    fun stopTimeline() {
        monitorJob?.cancel()
        monitorJob = null

        timeline = emptyList()
        lastSentIndex = -1
        anchorPositionMs = 0
        anchorSystemMs = SystemClock.elapsedRealtime()
        lastProcessedPositionMs = -1
    }

    /**
     * 타임라인 재생 상태 조회
     */
    @MainThread
    fun isTimelinePlaying(): Boolean {
        return timeline.isNotEmpty() && isEffectTransmissionEnabled
    }

    // ============================================================================================
    // 내부 유틸리티
    // ============================================================================================

    /**
     * LSEffectPayload의 byte[0](msgType, protocol v3)을 설정합니다.
     */
    private fun setMsgType(frame: ByteArray, msgType: Int): ByteArray {
        require(frame.size == 20) { "Frame must be 20 bytes" }
        require(msgType in 0..0xFF) { "msgType must be 0-255" }

        return frame.copyOf().apply {
            this[MSG_TYPE_BYTE_POSITION] = msgType.toByte()
        }
    }

    /**
     * LSEffectPayload의 byte[18-19](effectIndex, protocol v3, u16 LE)를 교체합니다.
     */
    private fun insertEffectIndex(frame: ByteArray, effectIndex: Int): ByteArray {
        require(frame.size == 20) { "Frame must be 20 bytes" }
        require(effectIndex in 0..0xFFFF) { "effectIndex must be 0-65535" }

        return frame.copyOf().apply {
            this[EFFECT_INDEX_BYTE_POSITION] = (effectIndex and 0xFF).toByte()
            this[EFFECT_INDEX_BYTE_POSITION + 1] = ((effectIndex shr 8) and 0xFF).toByte()
        }
    }

    // ============================================================================================
    // Cleanup
    // ============================================================================================

    override fun close() {
        stopTimeline()
        stop()
        scope.cancel()
    }
}
