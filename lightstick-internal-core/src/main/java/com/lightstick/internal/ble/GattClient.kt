package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
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
 * - 쓰기/읽기는 내부 직렬 큐(CmdQueueManager)로 순차 처리하여 충돌 방지.
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

    // GATT 작업 직렬 큐 (Write와 Read 모두 처리)
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
        mainHandler.post {
            // 기존 세션 정리
            disconnectInternal()

            val device = bluetoothAdapter.getRemoteDevice(address)
            val cb = ConnectCallbacks(onConnected, onFailed)
            pendingConnect[address] = cb

            // 타임아웃 설정
            val timeout = Runnable {
                val removed = pendingConnect.remove(address)
                if (removed != null) {
                    removed.onFailed(IllegalStateException("Connect timeout"))
                    disconnectInternal()
                }
            }
            connectTimeouts[address] = timeout
            mainHandler.postDelayed(timeout, 10_000)

            try {
                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, gattCallback)
                }
                currentAddress = address
            } catch (e: Throwable) {
                connectTimeouts.remove(address)?.let { mainHandler.removeCallbacks(it) }
                pendingConnect.remove(address)
                onFailed(e)
            }
        }
    }

    /**
     * 현재 GATT 연결 해제.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        Perms.ensureBtConnect(context)
        mainHandler.post { disconnectInternal() }
    }

    private fun disconnectInternal() {
        val addr = currentAddress
        if (addr != null) {
            queueManager.clear(addr)
            connectTimeouts.remove(addr)?.let { mainHandler.removeCallbacks(it) }
        }
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
        currentAddress = null
    }

    /**
     * 현재 세션 연결 여부.
     * - 상위 계층(예: Facade)에서 전송 전 빠른 가드용으로 사용 권장.
     */
    @Suppress("unused")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isConnected(): Boolean {
        Perms.ensureBtConnect(context)
        return gatt?.let {
            bluetoothManager.getConnectionState(it.device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
        } == true
    }

    /**
     * MTU 변경 요청 (API 21+).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestMtu(mtu: Int, onResult: (Result<Int>) -> Unit) {
        Perms.ensureBtConnect(context)
        val g = gatt ?: run {
            onResult(Result.failure(IllegalStateException("Not connected")))
            return
        }
        if (g.device.address != currentAddress) {
            onResult(Result.failure(IllegalStateException("Address mismatch")))
            return
        }
        pendingMtu[g.device.address] = onResult
        try {
            g.requestMtu(mtu)
        } catch (e: Throwable) {
            pendingMtu.remove(g.device.address)
            onResult(Result.failure(e))
        }
    }

    // --------------------------------------------------------------------------------------------
    // Read (이제 CmdQueueManager 사용)
    // --------------------------------------------------------------------------------------------

    /**
     * 특성 읽기 (이제 큐를 통해 직렬화됨).
     *
     * @param address 디바이스 주소 (현재 연결과 일치해야 함)
     * @param serviceUuid 서비스 UUID
     * @param charUuid 특성 UUID
     * @param onResult 읽기 결과 콜백
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
            onResult(Result.failure(IllegalStateException("Characteristic not found")))
            return
        }

        // Read 작업을 큐에 추가
        queueManager.enqueue(
            address = address,
            operation = "read"
        ) {
            pendingRead[address] = onResult
            try {
                val success = g.readCharacteristic(chr)
                if (!success) {
                    pendingRead.remove(address)
                    onResult(Result.failure(IllegalStateException("readCharacteristic() returned false")))
                    // 실패해도 다음 작업을 위해 complete 신호
                    queueManager.signalComplete(address)
                }
            } catch (e: Throwable) {
                pendingRead.remove(address)
                onResult(Result.failure(e))
                queueManager.signalComplete(address)
            }
        }
    }

    // --------------------------------------------------------------------------------------------
    // Write
    // --------------------------------------------------------------------------------------------

    /**
     * 특성 쓰기 (WITH_RESPONSE) - 큐를 통해 직렬화.
     *
     * 현재 프로젝트에서는 writeNoResponse()를 주로 사용하지만,
     * 향후 확실한 전송 보장이 필요한 경우를 위해 유지.
     */
    @Suppress("unused")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun writeCharacteristic(
        service: UUID,
        characteristic: UUID,
        data: ByteArray
    ): Boolean {
        Perms.ensureBtConnect(context)
        val g = gatt ?: return false
        val addr = g.device.address
        val chr = findCharacteristic(g, service, characteristic) ?: return false

        queueManager.enqueue(
            address = addr,
            operation = "write"
        ) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    g.writeCharacteristic(chr, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        chr.value = data
                        g.writeCharacteristic(chr)
                    }
                }
            } catch (_: Throwable) {
                queueManager.signalComplete(addr)
            }
        }
        return true
    }

    /**
     * NO_RESPONSE 쓰기 - Coalesce 지원.
     *
     * @param coalesceKey 같은 키의 대기 작업을 치환하려면 키 제공
     * @param replaceIfSameKey true면 같은 키의 기존 대기 항목 제거 후 최신 추가
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
            onResult(Result.failure(IllegalStateException("Characteristic not found")))
            return
        }

        val addr = g.device.address
        pendingEnableNotify[addr] = onResult

        try {
            val enabled = g.setCharacteristicNotification(chr, true)
            if (!enabled) {
                pendingEnableNotify.remove(addr)
                onResult(Result.failure(IllegalStateException("setCharacteristicNotification failed")))
                return
            }

            val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            val descriptor = chr.getDescriptor(cccdUuid) ?: run {
                pendingEnableNotify.remove(addr)
                onResult(Result.failure(IllegalStateException("CCCD descriptor not found")))
                return
            }

            pendingDescWrite[addr] = { descResult ->
                val enableCb = pendingEnableNotify.remove(addr)
                if (descResult.isSuccess) {
                    enableCb?.invoke(Result.success(Unit))
                } else {
                    enableCb?.invoke(Result.failure(descResult.exceptionOrNull() ?: IllegalStateException("Descriptor write failed")))
                }
            }

            // Descriptor write도 큐를 통해 직렬화
            queueManager.enqueue(
                address = addr,
                operation = "enableNotify"
            ) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(descriptor)
                        }
                    }
                } catch (e: Throwable) {
                    pendingDescWrite.remove(addr)?.invoke(Result.failure(e))
                    queueManager.signalComplete(addr)
                }
            }
        } catch (e: Throwable) {
            pendingEnableNotify.remove(addr)
            onResult(Result.failure(e))
        }
    }

    // --------------------------------------------------------------------------------------------
    // GATT Callback
    // --------------------------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    gatt.discoverServices()
                } catch (e: Throwable) {
                    connectTimeouts.remove(address)?.let { mainHandler.removeCallbacks(it) }
                    pendingConnect.remove(address)?.onFailed(e)
                }
            } else {
                connectTimeouts.remove(address)?.let { mainHandler.removeCallbacks(it) }
                val cb = pendingConnect.remove(address)
                cb?.onFailed(IllegalStateException("Connection failed or disconnected"))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            connectTimeouts.remove(address)?.let { mainHandler.removeCallbacks(it) }
            val cb = pendingConnect.remove(address)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cb?.onConnected?.invoke()
            } else {
                cb?.onFailed?.invoke(IllegalStateException("Service discovery failed: $status"))
            }
        }

        // Android 12 이하 호환성 유지
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
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
                // 실패해도 큐의 다음 작업 진행
                queueManager.signalComplete(address)
                return
            }
            val value: ByteArray = characteristic.value ?: ByteArray(0)
            pendingRead.remove(address)?.invoke(Result.success(value))
            // 성공 시에도 큐의 다음 작업 진행
            queueManager.signalComplete(address)
        }

        // Android 13+ (API 33)
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
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
                queueManager.signalComplete(address)
                return
            }
            pendingRead.remove(address)?.invoke(Result.success(value))
            queueManager.signalComplete(address)
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
}