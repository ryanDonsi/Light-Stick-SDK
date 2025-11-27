package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LED 제어를 위한 얇은 래퍼(Thin Layer).
 */
internal class LedControlManager(
    private val gattClient: GattClient
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playMutex = Mutex()

    @Volatile
    private var playJob: Job? = null

    // ============================================================================================
    // 내부 공통 전송 유틸
    // ============================================================================================

    /**
     * ✅ writeCharacteristic 호출로 변경
     */
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
    // 퍼블릭 API
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
        stop()
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

    override fun close() {
        stop()
        scope.cancel()
    }
}