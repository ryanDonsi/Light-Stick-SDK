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
import com.lightstick.internal.util.Log
import com.lightstick.internal.util.Perms
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 단일 기기(MAC 1개)와의 GATT 세션을 관리하는 클라이언트.
 *
 * 핵심 포인트:
 * - 연결은 항상 하나만 유지. 새로 연결 시 기존 세션 정리.
 * - 쓰기/읽기는 내부 직렬 큐(CmdQueueManager)로 순차 처리하여 충돌 방지.
 * - **Coalesce 지원**: 같은 키(coalesceKey)를 가진 대기 작업을 최신 데이터로 치환해
 *   지연을 최소화할 수 있음(replaceIfSameKey=true).
 * - 퍼미션은 모든 퍼블릭 API에서 보강(ensureBtConnect).
 * - AutoCloseable 구현: 리소스를 안전하게 정리.
 *
 * 실패/즉시 false 반환 대표 사례:
 * - gatt == null (연결 전/해제 후)
 * - 서비스/캐릭터리스틱 미발견 (서비스 디스커버리 전/UUID 불일치)
 */
@Suppress("DEPRECATION")
internal class GattClient(private val context: Context) : AutoCloseable {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var currentAddress: String? = null

    private var connectionStateListener: ((address: String, newState: Int, gattStatus: Int) -> Unit)? = null

    private data class ConnectCallbacks(
        val onConnected: () -> Unit,
        val onFailed: (Throwable) -> Unit
    )

    private val pendingConnect = ConcurrentHashMap<String, ConnectCallbacks>()
    private val pendingMtu = ConcurrentHashMap<String, (Result<Int>) -> Unit>()
    private val pendingRead = ConcurrentHashMap<String, (Result<ByteArray>) -> Unit>()
    private val pendingEnableNotify = ConcurrentHashMap<String, (Result<Unit>) -> Unit>()
    private val pendingDescWrite = ConcurrentHashMap<String, (Result<Unit>) -> Unit>()

    private val notificationListeners = ConcurrentHashMap<java.util.UUID, (ByteArray) -> Unit>()

    private val connectTimeouts = ConcurrentHashMap<String, Runnable>()

    private val queueManager = CmdQueueManager(
        CmdQueueConfig(
            minIntervalMs = 20L,
            maxQueueSizePerAddress = 64,
            overflowPolicy = OverflowPolicy.DROP_OLDEST
        )
    )

    // ============================================================================================
    // Public API
    // ============================================================================================

    /**
     * 현재 연결된 디바이스의 MAC 주소를 반환합니다.
     */
    fun getCurrentAddress(): String? = currentAddress

    fun addNotificationListener(charUuid: java.util.UUID, listener: (ByteArray) -> Unit) {
        notificationListeners[charUuid] = listener
    }

    fun removeNotificationListener(charUuid: java.util.UUID) {
        notificationListeners.remove(charUuid)
    }

    /**
     * 연결 상태 변경 리스너를 설정합니다.
     */
    fun setConnectionStateListener(
        listener: ((address: String, newState: Int, gattStatus: Int) -> Unit)?
    ) {
        this.connectionStateListener = listener
    }

    /**
     * 지정 MAC 주소로 GATT 연결을 시작.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(
        address: String,
        onConnected: () -> Unit,
        onFailed: (Throwable) -> Unit
    ) {
        Perms.ensureBtConnect(context)
        mainHandler.post {
            cleanup()

            val device = bluetoothAdapter.getRemoteDevice(address)
            val cb = ConnectCallbacks(onConnected, onFailed)
            pendingConnect[address] = cb

            val timeout = Runnable {
                val removed = pendingConnect.remove(address)
                if (removed != null) {
                    removed.onFailed(IllegalStateException("Connect timeout"))
                    cleanup()
                }
            }
            connectTimeouts[address] = timeout
            mainHandler.postDelayed(timeout, 10_000)

            try {
                gatt = device.connectGatt(
                    context,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
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
        mainHandler.post { cleanup() }
    }

    /**
     * AutoCloseable 구현: 모든 리소스를 정리하고 종료합니다.
     */
    override fun close() {
        cleanup()
    }

    /**
     * 내부 정리 메서드
     */
    private fun cleanup() {
        val addr = currentAddress

        if (addr != null) {
            queueManager.clear(addr)
            connectTimeouts.remove(addr)?.let { mainHandler.removeCallbacks(it) }
            pendingConnect.remove(addr)
            pendingMtu.remove(addr)
            pendingRead.remove(addr)
            pendingEnableNotify.remove(addr)
            pendingDescWrite.remove(addr)
        }
        notificationListeners.clear()

        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }

        gatt = null
        currentAddress = null
    }

    /**
     * 현재 세션 연결 여부.
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
     * Returns true if the connected device exposes [charUuid] under [serviceUuid].
     * Safe to call from any thread; returns false if not connected or services not discovered.
     */
    fun hasCharacteristic(serviceUuid: java.util.UUID, charUuid: java.util.UUID): Boolean =
        gatt?.getService(serviceUuid)?.getCharacteristic(charUuid) != null

    /**
     * MTU 변경 요청 (API 21+).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestMtu(mtu: Int, onResult: (Result<Int>) -> Unit): Boolean {
        Perms.ensureBtConnect(context)
        val g = gatt ?: return false

        val address = g.device.address
        pendingMtu[address] = onResult
        return try {
            g.requestMtu(mtu)
            true
        } catch (e: Throwable) {
            pendingMtu.remove(address)
            onResult(Result.failure(e))
            false
        }
    }

    // ============================================================================================
    // Read
    // ============================================================================================

    /**
     * ✅ 특성 읽기 (address 파라미터 제거)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readCharacteristic(
        serviceUuid: UUID,
        charUuid: UUID,
        onResult: (Result<ByteArray>) -> Unit
    ) {
        Perms.ensureBtConnect(context)
        val g = gatt ?: run {
            onResult(Result.failure(IllegalStateException("Not connected")))
            return
        }

        val address = g.device.address

        val chr = findCharacteristic(g, serviceUuid, charUuid) ?: run {
            onResult(Result.failure(IllegalStateException("Characteristic not found")))
            return
        }

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
                    queueManager.signalComplete(address)
                }
            } catch (e: Throwable) {
                pendingRead.remove(address)
                onResult(Result.failure(e))
                queueManager.signalComplete(address)
            }
        }
    }

    // ============================================================================================
    // Write
    // ============================================================================================

    /**
     * ✅ 특성 쓰기 (address 파라미터 제거, 내부적으로 gatt.device.address 사용)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun writeCharacteristic(
        serviceUuid: UUID,
        charUuid: UUID,
        data: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        replaceIfSameKey: Boolean = false,
        coalesceKey: String? = null
    ): Boolean {
        Perms.ensureBtConnect(context)
        val g = gatt ?: return false

        val address = g.device.address  // ✅ 내부에서 주소 가져오기

        val chr = findCharacteristic(g, serviceUuid, charUuid) ?: return false
        chr.writeType = writeType

        val key = coalesceKey ?: "$serviceUuid/$charUuid"
        queueManager.enqueue(
            address = address,
            operation = "write",
            replaceIfSameKey = replaceIfSameKey,
            coalesceKey = key
        ) {
            chr.value = data
            try {
                val success = g.writeCharacteristic(chr)
                if (!success) {
                    queueManager.signalComplete(address)
                }
            } catch (e: Throwable) {
                queueManager.signalComplete(address)
            }
        }
        return true
    }

    // ============================================================================================
    // Notification / CCCD
    // ============================================================================================

    /**
     * ✅ Notification 활성화/비활성화 (address 파라미터 제거)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setCharacteristicNotification(
        serviceUuid: UUID,
        charUuid: UUID,
        enable: Boolean,
        onResult: (Result<Unit>) -> Unit
    ) {
        Perms.ensureBtConnect(context)
        val g = gatt ?: run {
            onResult(Result.failure(IllegalStateException("Not connected")))
            return
        }

        val address = g.device.address  // ✅ 내부에서 주소 가져오기

        val chr = findCharacteristic(g, serviceUuid, charUuid) ?: run {
            onResult(Result.failure(IllegalStateException("Characteristic not found")))
            return
        }

        try {
            val notifyOk = g.setCharacteristicNotification(chr, enable)
            if (!notifyOk) {
                onResult(Result.failure(IllegalStateException("setCharacteristicNotification returned false")))
                return
            }
        } catch (e: Throwable) {
            onResult(Result.failure(e))
            return
        }

        val desc = chr.getDescriptor(UuidConstants.CCCD) ?: run {
            onResult(Result.failure(IllegalStateException("CCCD not found")))
            return
        }

        queueManager.enqueue(address, "writeCCCD") {
            val value = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            desc.value = value
            pendingDescWrite[address] = onResult

            try {
                val success = g.writeDescriptor(desc)
                if (!success) {
                    pendingDescWrite.remove(address)
                    onResult(Result.failure(IllegalStateException("writeDescriptor returned false")))
                    queueManager.signalComplete(address)
                }
            } catch (e: Throwable) {
                pendingDescWrite.remove(address)
                onResult(Result.failure(e))
                queueManager.signalComplete(address)
            }
        }
    }

    // ============================================================================================
    // GATT Callback
    // ============================================================================================

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            val stateStr = when (newState) {
                BluetoothProfile.STATE_CONNECTED    -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING   -> "CONNECTING"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "UNKNOWN($newState)"
            }
            // status=19(0x13)=PEER_TERMINATED, status=8(0x08)=CONN_TIMEOUT, status=0=SUCCESS
            Log.d("[GattClient] onConnectionStateChange: $address state=$stateStr status=$status(0x${status.toString(16)})")

            connectionStateListener?.invoke(address, newState, status)

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("[GattClient] 서비스 디스커버리 시작: $address")
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) {
                    connectTimeouts.remove(address)?.let { mainHandler.removeCallbacks(it) }
                    pendingConnect.remove(address)?.onFailed(SecurityException("Permission denied for discoverServices", e))
                } catch (e: Throwable) {
                    connectTimeouts.remove(address)?.let { mainHandler.removeCallbacks(it) }
                    pendingConnect.remove(address)?.onFailed(e)
                }
            } else {
                if (status != BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.w("[GattClient] 비정상 연결 해제: $address status=$status(0x${status.toString(16)}) — " +
                        when (status) {
                            0x08 -> "연결 타임아웃 (CONN_TIMEOUT)"
                            0x13 -> "피어 기기 연결 종료 (PEER_TERMINATED) — OTA 중 발생 시 디바이스 측 오류 확인 필요"
                            0x16 -> "로컬 호스트 종료 (LOCAL_HOST_TERMINATED)"
                            0x3E -> "연결 실패 (CONN_FAILED_ESTABLISH)"
                            0x85, 0x101 -> "GATT 연결 타임아웃"
                            else -> "알 수 없는 상태 코드"
                        })
                }
                connectTimeouts.remove(address)?.let { mainHandler.removeCallbacks(it) }
                val cb = pendingConnect.remove(address)
                cb?.onFailed(IllegalStateException("Connection failed or disconnected: status=$status"))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            connectTimeouts.remove(address)?.let { mainHandler.removeCallbacks(it) }
            val cb = pendingConnect.remove(address)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val serviceUuids = gatt.services.map { it.uuid.toString().takeLast(8) }
                Log.d("[GattClient] 서비스 디스커버리 완료: $address 서비스=${serviceUuids}")
                cb?.onConnected?.invoke()
            } else {
                Log.w("[GattClient] 서비스 디스커버리 실패: $address status=$status")
                cb?.onFailed?.invoke(IllegalStateException("Service discovery failed: $status"))
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
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
                queueManager.signalComplete(address)
                return
            }
            val value: ByteArray = characteristic.value ?: ByteArray(0)
            pendingRead.remove(address)?.invoke(Result.success(value))
            queueManager.signalComplete(address)
        }

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

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            notificationListeners[characteristic.uuid]?.invoke(characteristic.value ?: return)
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            notificationListeners[characteristic.uuid]?.invoke(value)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w("[GattClient] onCharacteristicWrite 실패: ${gatt.device.address} " +
                    "char=${characteristic.uuid.toString().takeLast(8)} status=$status(0x${status.toString(16)})")
            }
            queueManager.signalComplete(gatt.device.address)
        }

        @Suppress("OVERRIDE_DEPRECATION")
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
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("[GattClient] MTU 협상 완료: $address mtu=$mtu")
            } else {
                Log.w("[GattClient] MTU 협상 실패: $address status=$status — 기본 MTU(23)로 동작 가능성")
            }
            pendingMtu.remove(address)?.invoke(
                if (status == BluetoothGatt.GATT_SUCCESS) Result.success(mtu)
                else Result.failure(IllegalStateException("onMtuChanged failed: $status"))
            )
        }
    }

    // ============================================================================================
    // Internals
    // ============================================================================================

    private fun findCharacteristic(
        g: BluetoothGatt,
        serviceUuid: UUID,
        charUuid: UUID
    ): BluetoothGattCharacteristic? {
        val svc = g.getService(serviceUuid) ?: return null
        return svc.getCharacteristic(charUuid)
    }

    private fun buildPropertiesString(props: Int): String {
        val flags = mutableListOf<String>()
        if (props and BluetoothGattCharacteristic.PROPERTY_READ        != 0) flags += "READ"
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE       != 0) flags += "WRITE"
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) flags += "WRITE_NO_RSP"
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY      != 0) flags += "NOTIFY"
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE    != 0) flags += "INDICATE"
        if (props and BluetoothGattCharacteristic.PROPERTY_BROADCAST   != 0) flags += "BROADCAST"
        if (props and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) flags += "SIGNED_WRITE"
        if (props and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) flags += "EXT_PROPS"
        return flags.joinToString("|")
    }
}