package com.lightstick.internal.ble.ota

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
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
        private const val MIN_PAYLOAD = 20
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
                Log.i("OTA 준비 시작")

                // 1) MTU 협상
                val mtu = if (preferredMtu > 0) suspendMtu(preferredMtu) else DEFAULT_MTU
                Log.d("MTU 협상 결과: $mtu")

                // 2) Notification 활성화
                suspendEnableNotify(serviceUuid, dataCharUuid)
                Log.d("Notification 활성화 완료")

                // 3) START Opcode 전송
                if (startOpcodes?.isNotEmpty() == true) {
                    val ok = gatt.writeCharacteristic(
                        serviceUuid = serviceUuid,
                        charUuid = dataCharUuid,
                        data = startOpcodes,
                        writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    )
                    if (!ok) error("START Opcode 전송 실패")
                    Log.d("START Opcode 전송 완료")
                    delay(500)
                }

                _state.value = OtaState.TRANSFER
                Log.i("펌웨어 전송 시작")

                // 4) 펌웨어 데이터 전송
                val payloadMax = (mtu - GATT_HEADER_LEN).coerceAtLeast(MIN_PAYLOAD)
                var sent = 0
                val total = firmware.size
                var lastReported = -1

                while (sent < total) {
                    if (abortFlag) error("사용자 중단 요청")

                    val end = min(sent + payloadMax, total)
                    val chunk = firmware.copyOfRange(sent, end)

                    val ok = gatt.writeCharacteristic(
                        serviceUuid = serviceUuid,
                        charUuid = dataCharUuid,
                        data = chunk,
                        writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    )
                    if (!ok) error("GATT Write 실패: $sent/$total")

                    sent = end

                    val progress = ((sent.toLong() * 100L) / total.toLong()).toInt()
                    if (progress != lastReported) {
                        lastReported = progress
                        onProgress(progress)
                    }

                    delay(10)
                }

                if (lastReported != 100) onProgress(100)
                _state.value = OtaState.COMPLETE
                Log.i("OTA 전송 완료")
                onResult(Result.success(Unit))
            } catch (t: Throwable) {
                _state.value = if (abortFlag) OtaState.ABORTED else OtaState.ERROR
                if (abortFlag) Log.w("OTA 중단됨: ${t.message}")
                else Log.e("OTA 오류 발생: ${t.message}", t)
                onResult(Result.failure(t))
            }
        }
    }

    fun abort() {
        abortFlag = true
        job?.cancel()
        Log.i("OTA 중단 요청")
    }

    override fun close() {
        abort()
        scope.cancel()
    }

    // ============================================================================================
    // Suspend Helpers
    // ============================================================================================

    /**
     * ✅ SecurityException 명시적 처리
     */
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

    /**
     * ✅ SecurityException 명시적 처리
     */
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
}