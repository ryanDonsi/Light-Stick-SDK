package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LED 제어 및 타임라인 재생을 위한 매니저
 *
 * ✅ 주요 기능:
 * - 타임라인 기반 이펙트 재생 (나노초 정확도)
 * - 음악 재생 위치 동기화
 * - Effect 전송 ON/OFF 제어
 * - syncIndex 자동 관리 (사용자 투명)
 * - Seek 자동 감지
 */
internal class LedControlManager(
    private val gattClient: GattClient
) : AutoCloseable {

    companion object {
        private const val TAG = "LedControlManager"
        private const val EFFECT_INDEX_BYTE_POSITION = 0  // LSEffectPayload의 effectIndex 위치 (0-1번 바이트, u16 Little Endian)
        private const val SYNC_INDEX_BYTE_POSITION = 19  // LSEffectPayload의 syncIndex 위치
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playMutex = Mutex()

    // =========== Timeline 재생 상태 ===========
    @Volatile private var timeline: List<Pair<Long, ByteArray>> = emptyList()
    @Volatile private var currentPlaybackPositionMs: Long = 0
    @Volatile private var lastProcessedPositionMs: Long = -1
    @Volatile private var lastSentIndex: Int = -1

    // =========== Effect 전송 제어 ===========
    @Volatile private var isEffectTransmissionEnabled: Boolean = true
    @Volatile private var currentSyncIndex: Int = 1

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
            data = bytes20,
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
                        data = frame,
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
     * 1. effectIndex를 1부터 순차적으로 재계산 (펌웨어 순차성 보장)
     * 2. syncIndex가 자동으로 증가 (새로운 재생 세션 시작)
     *
     * @param frames 타임라인 엔트리 리스트 (timestampMs, 20B payload)
     */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun loadTimeline(frames: List<Pair<Long, ByteArray>>) {
        require(frames.all { it.second.size == 20 }) { "All frames must be 20 bytes" }

        stopTimeline()

        val sortedFrames = frames.sortedBy { it.first }

        // ✅ effectIndex를 1부터 순차적으로 재계산 (펌웨어 순차성 보장)
        timeline = sortedFrames.mapIndexed { index, (timestamp, frame) ->
            val newEffectIndex = index + 1
            val updatedFrame = updateEffectIndex(frame, newEffectIndex)
            timestamp to updatedFrame
        }

        lastSentIndex = -1
        currentPlaybackPositionMs = 0
        lastProcessedPositionMs = -1
        isEffectTransmissionEnabled = true

        // ✅ 새 타임라인 로드 시 syncIndex 자동 증가
        currentSyncIndex = (currentSyncIndex % 255) + 1

        Log.d(TAG, "✅ Timeline loaded: ${timeline.size} frames, " +
                "effectIndex: 1~${timeline.size}, syncIndex=$currentSyncIndex")
    }

    /**
     * 현재 음악 재생 위치를 업데이트합니다.
     *
     * 이 메서드는 주기적으로 호출되어야 하며 (권장: 100ms),
     * SDK는 내부적으로 각 이펙트를 정확한 타이밍에 전송합니다.
     *
     * @param currentPositionMs 현재 음악 재생 위치 (밀리초)
     */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun updatePlaybackPosition(currentPositionMs: Long) {
        if (timeline.isEmpty()) return

        currentPlaybackPositionMs = currentPositionMs

        // ✅ Seek 감지 (뒤로 1초 이상)
        if (currentPositionMs < lastProcessedPositionMs - 1000) {
            lastSentIndex = timeline.indexOfLast { it.first <= currentPositionMs }
            Log.d(TAG, "⏪ Seek backward detected: position=${currentPositionMs}ms, " +
                    "reset index to $lastSentIndex")
        }

        // ✅ Seek 감지 (앞으로 10초 이상)
        if (currentPositionMs > lastProcessedPositionMs + 10000) {
            lastSentIndex = timeline.indexOfLast { it.first <= currentPositionMs }
            Log.d(TAG, "⏩ Seek forward detected: position=${currentPositionMs}ms, " +
                    "reset index to $lastSentIndex")
        }

        lastProcessedPositionMs = currentPositionMs

        // ✅ OFF 상태면 인덱스만 업데이트, 전송 스킵
        if (!isEffectTransmissionEnabled) {
            // 타임라인 추적 유지
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
                // ✅ syncIndex 삽입 (effectIndex는 loadTimeline에서 이미 순차적으로 설정됨)
                val frameWithSync = insertSyncIndex(frame, currentSyncIndex)

                val ok = sendNoResponseCoalesced(
                    serviceUuid = UuidConstants.LCS_SERVICE,
                    charUuid = UuidConstants.LCS_PAYLOAD,
                    data = frameWithSync,
                    coalesceKey = "LCS:PAYLOAD"
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
            val effectIndexStart = lastSentIndex - transmittedCount + 1
            val effectIndexEnd = lastSentIndex
            Log.d(TAG, "📤 Transmitted $transmittedCount effects at ${currentPositionMs}ms " +
                    "(effectIndex: ${effectIndexStart + 1}~${effectIndexEnd + 1}, syncIndex=$currentSyncIndex)")
        }
    }

    /**
     * 이펙트 전송을 일시정지합니다.
     *
     * 타임라인 추적은 계속되지만 BLE 전송만 중단됩니다.
     */
    @MainThread
    fun pauseEffects() {
        if (!isEffectTransmissionEnabled) {
            Log.d(TAG, "⏸ Effects already paused")
            return
        }

        isEffectTransmissionEnabled = false
        Log.d(TAG, "⏸ Effects paused (syncIndex=$currentSyncIndex)")
    }

    /**
     * 이펙트 전송을 재개합니다.
     *
     * 내부적으로 syncIndex가 자동으로 증가하여 디바이스 재동기화가 처리됩니다.
     */
    @MainThread
    fun resumeEffects() {
        if (isEffectTransmissionEnabled) {
            Log.d(TAG, "▶️ Effects already playing")
            return
        }

        // ✅ syncIndex 자동 증가 (재동기화)
        currentSyncIndex = (currentSyncIndex % 255) + 1
        isEffectTransmissionEnabled = true

        Log.d(TAG, "▶️ Effects resumed with syncIndex=$currentSyncIndex (resync)")
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
        currentPlaybackPositionMs = 0
        lastProcessedPositionMs = -1

        Log.d(TAG, "⏹ Timeline stopped")
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
     * LSEffectPayload의 0-1번째 바이트(effectIndex)를 업데이트
     *
     * effectIndex는 Little Endian으로 저장됨 (u16)
     */
    private fun updateEffectIndex(frame: ByteArray, effectIndex: Int): ByteArray {
        require(frame.size == 20) { "Frame must be 20 bytes" }
        require(effectIndex in 0..0xFFFF) { "effectIndex must be 0-65535" }

        return frame.copyOf().apply {
            // Little Endian: low byte first, high byte second
            this[0] = (effectIndex and 0xFF).toByte()
            this[1] = ((effectIndex shr 8) and 0xFF).toByte()
        }
    }

    /**
     * LSEffectPayload의 19번째 바이트(syncIndex)를 교체
     */
    private fun insertSyncIndex(frame: ByteArray, syncIndex: Int): ByteArray {
        require(frame.size == 20) { "Frame must be 20 bytes" }
        require(syncIndex in 0..255) { "syncIndex must be 0-255" }

        return frame.copyOf().apply {
            this[SYNC_INDEX_BYTE_POSITION] = syncIndex.toByte()
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