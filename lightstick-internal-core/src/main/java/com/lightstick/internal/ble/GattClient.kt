package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.lightstick.internal.ble.queue.CmdQueueConfig
import com.lightstick.internal.ble.queue.CmdQueueManager
import com.lightstick.internal.ble.queue.OverflowPolicy
import com.lightstick.internal.util.Perms
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 단일 기기(MAC 1개)와의 GATT 세션을 관리하는 클라이언트.
 *
 * 핵심 포인트
 * - 연결은 항상 하나만 유지. 새로 연결 시 기존 세션 정리.
 * - 쓰기는 내부 직렬 큐(CmdQueueManager)로 순차 처리하여 충돌 방지.
 * - **Coalesce 지원**: 같은 키(coalesceKey)를 가진 대기 작업을 최신 데이터로 치환해
 *   지연을 최소화할 수 있음(replaceIfSameKey=true).
 * - 퍼미션은 모든 퍼블릭 API에서 보강(ensureBtConnect).
 *
 * 실패/즉시 false 반환 대표 사례
 * - gatt == null (연결 전/해제 후)
 * - 서비스/캐릭터리스틱 미발견 (서비스 디스커버리 전/UUID 불일치)
 */
@Suppress("DEPRECATION")
internal class GattClient(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var currentAddress: String? = null

    private data class ConnectCallbacks(
        val onConnected: () -> Unit,
        val onFailed: (Throwable) -> Unit
    )

    // 주소별 보류 콜백 맵
    private val pendingConnect = ConcurrentHashMap<String, ConnectCallbacks>()
    private val pendingMtu = ConcurrentHashMap<String, (Result<Int>) -> Unit>()
    private val pendingRead = ConcurrentHashMap<String, (Result<ByteArray>) -> Unit>()
    private val pendingEnableNotify = ConcurrentHashMap<String, (Result<Unit>) -> Unit>()
    private val pendingDescWrite = ConcurrentHashMap<String, (Result<Unit>) -> Unit>()

    private val connectTimeouts = ConcurrentHashMap<String, Runnable>()

    // GATT 작업 직렬 큐 (Coalesce는 enqueue에서 제어)
    private val queueManager = CmdQueueManager(
        CmdQueueConfig(
            minIntervalMs = 20L,
            maxQueueSizePerAddress = 64,
            overflowPolicy = OverflowPolicy.DROP_OLDEST
        )
    )

    // --------------------------------------------------------------------------------------------
    // Public API
    // --------------------------------------------------------------------------------------------

    /**
     * 지정 MAC 주소로 GATT 연결을 시작.
     *
     * - 기존 세션이 있으면 정리 후 시도.
     * - 서비스 디스커버리 완료 시 onConnected 콜백.
     * - 10초 타임아웃(미완료 시 실패).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(
        address: String,
        onConnected: () -> Unit,
        onFailed: (Throwable) -> Unit
    ) {
        Perms.ensureBtConnect(context)
        disconnectInternal()

        val device = try {
            bluetoothAdapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            onFailed(IllegalArgumentException("Invalid MAC address: $address", e))
            return
        }

        currentAddress = address
        pendingConnect[address] = ConnectCallbacks(onConnected, onFailed)

        val timeout = Runnable {
            if (pendingConnect.remove(address) != null) {
                disconnectInternal()
                onFailed(TimeoutException("GATT connect timeout"))
            }
        }
        connectTimeouts[address] = timeout
        mainHandler.postDelayed(timeout, 10_000L)

        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (se: SecurityException) {
            pendingConnect.remove(address)
            connectTimeouts.remove(address)?.let(mainHandler::removeCallbacks)
            onFailed(se)
        } catch (t: Throwable) {
            pendingConnect.remove(address)
            connectTimeouts.remove(address)?.let(mainHandler::removeCallbacks)
            onFailed(t)
        }
    }

    /**
     * 현재 연결을 종료(안전 호출).
     * - 큐/콜백/리소스까지 정리.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        Perms.ensureBtConnect(context)
        disconnectInternal()
    }

    /**
     * 현재 세션 연결 여부.
     * - 상위 계층(예: Facade)에서 전송 전 빠른 가드용으로 사용 권장.
     */
    @Suppress("unused")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isConnected(): Boolean =
        gatt?.let { bluetoothManager.getConnectionState(it.device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED } == true

    /**
     * MTU 요청.
     * - 성공 시 negotiated MTU 콜백.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestMtu(preferred: Int, onResult: (Result<Int>) -> Unit) {
        Perms.ensureBtConnect(context)
        val g = gatt ?: run {
            onResult(Result.failure(IllegalStateException("Not connected")))
            return
        }
        val address = g.device.address
        pendingMtu[address] = onResult
        try {
            val ok = g.requestMtu(preferred)
            if (!ok) {
                pendingMtu.remove(address)?.invoke(Result.failure(IllegalStateException("requestMtu() returned false")))
            }
        } catch (se: SecurityException) {
            pendingMtu.remove(address)?.invoke(Result.failure(se))
        }
    }

    // --------------------------------------------------------------------------------------------
    // Write (NO_RESPONSE + Coalesce 지원)
    // --------------------------------------------------------------------------------------------

    /**
     * NO_RESPONSE 쓰기를 큐에 enqueue한다.
     *
     * @param service          서비스 UUID
     * @param characteristic   캐릭터리스틱 UUID
     * @param data             전송 데이터
     * @param coalesceKey      (선택) 같은 키면 대기 중 작업을 최신으로 치환
     * @param replaceIfSameKey true면 같은 키 대기 작업을 **즉시 대체**(최신만 유지)
     *
     * @return true면 큐 투입 성공(실제 완료는 onCharacteristicWrite에서 신호), false면 즉시 실패
     *
     * false가 되는 전형적 경우:
     * - 연결 없음(gatt==null)
     * - 서비스/캐릭터리스틱 미발견(서비스 디스커버리 전/UUID 불일치)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun writeNoResponse(
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
        coalesceKey: String? = null,
        replaceIfSameKey: Boolean = false
    ): Boolean {
        Perms.ensureBtConnect(context)

        val g = gatt ?: return false
        val addr = g.device.address
        val chr = findCharacteristic(g, service, characteristic) ?: return false

        queueManager.enqueue(
            address = addr,
            operation = "writeNoRsp",
            coalesceKey = coalesceKey,            // ★ Coalesce 핵심
            replaceIfSameKey = replaceIfSameKey   // ★ Coalesce 핵심
        ) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    g.writeCharacteristic(chr, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        chr.value = data
                        g.writeCharacteristic(chr)
                    }
                }
            } catch (_: Throwable) {
                // 예외 시 다음 작업 진행을 위해 반드시 신호
                queueManager.signalComplete(addr)
            }
        }
        return true
    }

    // --------------------------------------------------------------------------------------------
    // Notify / Descriptor
    // --------------------------------------------------------------------------------------------

    /**
     * Notifications 활성화: setCharacteristicNotification(true) + CCCD(0x2902)=0x0100
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun enableNotifications(
        serviceUuid: UUID,
        charUuid: UUID,
        onResult: (Result<Unit>) -> Unit
    ) {
        Perms.ensureBtConnect(context)
        val g = gatt ?: run {
            onResult(Result.failure(IllegalStateException("Not connected")))
            return
        }
        if (g.device.address != currentAddress) {
            onResult(Result.failure(IllegalStateException("Address mismatch")))
            return
        }
        val chr = findCharacteristic(g, serviceUuid, charUuid) ?: run {
            onResult(Result.failure(IllegalStateException("Characteristic not found: $charUuid")))
            return
        }
        val addr = g.device.address
        pendingEnableNotify[addr] = onResult

        queueManager.enqueue(
            address = addr,
            operation = "enableNotify",
            coalesceKey = "notify:$charUuid",   // enable 동작은 자체적으로 중복 치환 가능
            replaceIfSameKey = true
        ) {
            try {
                @Suppress("MissingPermission")
                g.setCharacteristicNotification(chr, true)
                // CCCD: Notifications enable(0x0100) — 상수로 명확화
                writeDescriptor(
                    g = g,
                    chr = chr,
                    descUuid = UuidConstants.CCCD,
                    value = UuidConstants.CCCD_ENABLE_NOTIFICATION
                )
            } catch (se: SecurityException) {
                pendingEnableNotify.remove(addr)?.invoke(Result.failure(se))
                queueManager.signalComplete(addr)
            } catch (t: Throwable) {
                pendingEnableNotify.remove(addr)?.invoke(Result.failure(t))
                queueManager.signalComplete(addr)
            }
        }
    }

    /**
     * 내부 Descriptor 쓰기 유틸.
     * - enableNotifications() 경로에서 호출되며, 성공/실패를 pendingDescWrite → pendingEnableNotify로 전달한다.
     * - 퍼미션 보강 및 Lint 억제를 함수 내부에서 처리한다.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun writeDescriptor(
        g: BluetoothGatt,
        chr: BluetoothGattCharacteristic,
        descUuid: UUID,
        value: ByteArray
    ) {
        Perms.ensureBtConnect(context)

        val desc = chr.getDescriptor(descUuid)
        val addr = g.device.address
        if (desc == null) {
            pendingEnableNotify.remove(addr)?.invoke(
                Result.failure(IllegalStateException("Descriptor not found: $descUuid"))
            )
            queueManager.signalComplete(addr)
            return
        }

        // enableNotifications 경유인 경우, desc write 성공 시 최종 성공 처리
        pendingDescWrite[addr] = { res ->
            pendingEnableNotify.remove(addr)?.invoke(res)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                @Suppress("MissingPermission")
                g.writeDescriptor(desc, value)
            } else {
                @Suppress("DEPRECATION", "MissingPermission")
                run {
                    desc.value = value
                    g.writeDescriptor(desc)
                }
            }
        } catch (se: SecurityException) {
            pendingDescWrite.remove(addr)?.invoke(Result.failure(se))
            queueManager.signalComplete(addr)
        } catch (t: Throwable) {
            pendingDescWrite.remove(addr)?.invoke(Result.failure(t))
            queueManager.signalComplete(addr)
        }
    }

    // --------------------------------------------------------------------------------------------
    // Generic Read
    // --------------------------------------------------------------------------------------------

    /**
     * 지정 특성 읽기. 연결/주소/UUID 검증 후 읽기 결과를 콜백으로 반환.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readCharacteristic(
        address: String,
        serviceUuid: UUID,
        charUuid: UUID,
        onResult: (Result<ByteArray>) -> Unit
    ) {
        Perms.ensureBtConnect(context)

        val g = gatt ?: run {
            onResult(Result.failure(IllegalStateException("Not connected")))
            return
        }
        if (g.device.address != address) {
            onResult(Result.failure(IllegalStateException("Address mismatch")))
            return
        }
        val chr = findCharacteristic(g, serviceUuid, charUuid) ?: run {
            onResult(Result.failure(IllegalStateException("Characteristic not found: $charUuid")))
            return
        }

        pendingRead[address] = onResult

        try {
            val ok: Boolean = g.readCharacteristic(chr)
            if (!ok) {
                pendingRead.remove(address)?.invoke(
                    Result.failure(IllegalStateException("readCharacteristic returned false"))
                )
            }
        } catch (se: SecurityException) {
            pendingRead.remove(address)?.invoke(Result.failure(se))
        } catch (t: Throwable) {
            pendingRead.remove(address)?.invoke(Result.failure(t))
        }
    }

    // --------------------------------------------------------------------------------------------
    // GATT Callback
    // --------------------------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectTimeouts.remove(address)?.let(mainHandler::removeCallbacks)
                    try {
                        gatt.discoverServices()
                    } catch (_: SecurityException) {
                        val cb = pendingConnect.remove(address)
                        disconnectInternal()
                        cb?.onFailed?.invoke(SecurityException("discoverServices requires BLUETOOTH_CONNECT"))
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    cleanupAfterDisconnect(address, gatt)
                    if (pendingConnect.remove(address) != null && status != BluetoothGatt.GATT_SUCCESS) {
                        connectTimeouts.remove(address)?.let(mainHandler::removeCallbacks)
                    }
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            val cb = pendingConnect.remove(address)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cb?.onConnected?.invoke()
            } else {
                cb?.onFailed?.invoke(IllegalStateException("onServicesDiscovered failed: $status"))
                disconnectInternal()
            }
        }

        // API < 33
        @Deprecated("Deprecated in API 33, kept for compatibility.")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val address = gatt.device.address
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pendingRead.remove(address)?.invoke(
                    Result.failure(IllegalStateException("Read failed: $status"))
                )
                return
            }
            val value: ByteArray = characteristic.value ?: ByteArray(0)
            pendingRead.remove(address)?.invoke(Result.success(value))
        }

        // API 33+
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val address = gatt.device.address
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pendingRead.remove(address)?.invoke(
                    Result.failure(IllegalStateException("Read failed: $status"))
                )
                return
            }
            pendingRead.remove(address)?.invoke(Result.success(value))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // 직렬화 큐 진행 (성공/실패 무관하게 다음 작업으로)
            queueManager.signalComplete(gatt.device.address)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val address = gatt.device.address
            val cb = pendingDescWrite.remove(address)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cb?.invoke(Result.success(Unit))
            } else {
                cb?.invoke(Result.failure(IllegalStateException("Descriptor write failed: $status")))
            }
            queueManager.signalComplete(address)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val address = gatt.device.address
            pendingMtu.remove(address)?.invoke(
                if (status == BluetoothGatt.GATT_SUCCESS) Result.success(mtu)
                else Result.failure(IllegalStateException("onMtuChanged failed: $status"))
            )
        }
    }

    // --------------------------------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------------------------------

    private fun findCharacteristic(
        g: BluetoothGatt,
        serviceUuid: UUID,
        charUuid: UUID
    ): BluetoothGattCharacteristic? {
        val svc = g.getService(serviceUuid) ?: return null
        return svc.getCharacteristic(charUuid)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun disconnectInternal() {
        currentAddress?.let { connectTimeouts.remove(it)?.let(mainHandler::removeCallbacks) }

        gatt?.let { g ->
            try { g.disconnect() } catch (_: SecurityException) {}
            try { g.close() } catch (_: Throwable) {}
        }

        currentAddress?.let { queueManager.clear(it) }
        currentAddress = null
        gatt = null

        pendingConnect.clear()
        pendingMtu.clear()
        pendingRead.clear()
        pendingEnableNotify.clear()
        pendingDescWrite.clear()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun cleanupAfterDisconnect(address: String, gatt: BluetoothGatt) {
        connectTimeouts.remove(address)?.let(mainHandler::removeCallbacks)
        queueManager.clear(address)
        try { gatt.close() } catch (_: Throwable) {}
        if (currentAddress == address) currentAddress = null
        if (this.gatt == gatt) this.gatt = null
    }

    private class TimeoutException(message: String) : RuntimeException(message)
}
