package com.lightstick.internal.api

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import com.lightstick.internal.ble.*
import com.lightstick.internal.ble.ota.OtaManager
import com.lightstick.internal.ble.ota.OtaState
import com.lightstick.internal.ble.state.InternalConnectionState
import com.lightstick.internal.ble.state.InternalDeviceInfo
import com.lightstick.internal.ble.state.InternalDeviceState
import com.lightstick.internal.ble.state.InternalDisconnectReason
import com.lightstick.internal.efx.EfxBinary
import com.lightstick.internal.efx.MusicIdProvider
import com.lightstick.internal.event.DeviceEventRegistry
import com.lightstick.internal.event.EventRouter
import com.lightstick.internal.event.GlobalEventRegistry
import com.lightstick.internal.event.InternalRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import android.util.Log
import com.lightstick.internal.ble.DeviceFilter
import android.bluetooth.BluetoothManager
import com.lightstick.internal.ble.state.InternalDeviceStateEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * 내부 Facade.
 *
 * Note: This object is public (no 'internal' modifier) because it must be
 * accessible from the public module (lightstick) which depends on this
 * internal-core module.
 */
@SuppressLint("StaticFieldLeak")
object Facade {

    // ============================================================================================
    // 초기화 / 컨텍스트
    // ============================================================================================

    private lateinit var appContext: Context
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var eventInitialized = false

    private lateinit var deviceStateManager: DeviceStateManager

    private lateinit var globalDeviceFilter: DeviceFilter
    private var allowUnknownDevices: Boolean = false
    private fun requireInit() {
        check(::appContext.isInitialized) { "Facade.initialize(context) must be called first." }
    }

    @MainThread
    fun initialize(
        context: Context,
        filter: DeviceFilter? = null,
        allowUnknownDevices: Boolean = false
    ) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        scan = ScanManager()
        bond = BondManager()

        globalDeviceFilter = filter ?: DeviceFilter.acceptAll()
        this.allowUnknownDevices = allowUnknownDevices


        val deviceFilter: (String, String?, Int?) -> Boolean = { mac, name, rssi ->
            isDeviceAllowed(mac, name, rssi)
        }

        deviceStateManager = DeviceStateManager(
            context = appContext,
            deviceFilter = deviceFilter
        )

        EventRouter.initialize(appContext)
        eventInitialized = true
    }

    /**
     * Checks if a device is allowed by the global filter.
     *
     * @param mac Device MAC address.
     * @param name Device name (nullable).
     * @param rssi Device RSSI (nullable).
     * @return `true` if the device passes the filter; `false` otherwise.
     */
    fun isDeviceAllowed(mac: String, name: String?, rssi: Int? = null): Boolean {
        return if (::globalDeviceFilter.isInitialized) {
            if (name == null && !allowUnknownDevices) {
                false
            } else {
                globalDeviceFilter.matches(mac, name, rssi)
            }
        } else {
            true
        }
    }

    // ============================================================================================
    // 매니저 (stateless)
    // ============================================================================================

    private lateinit var scan: ScanManager
    private lateinit var bond: BondManager

    // ============================================================================================
    // 세션 (멀티 디바이스 관리)
    // ============================================================================================

    private data class Session(
        val gatt: GattClient,
        val led: LedControlManager,
        val deviceInfo: DeviceInfoManager,
        val ota: OtaManager?
    ) {
        fun cleanup() {
            runCatching { ota?.abort() }
            runCatching { led.close() }
            runCatching { gatt.close() }
        }
    }

    private val sessions: MutableMap<String, Session> = ConcurrentHashMap()

    private val lastSeenName = ConcurrentHashMap<String, String>()
    private val lastSeenRssi = ConcurrentHashMap<String, Int>()

    private fun requireSession(mac: String): Session =
        sessions[mac] ?: error("No active session for $mac")

    private fun removeSession(mac: String) {
        sessions.remove(mac)?.let { session ->
            session.cleanup()
        }
        deviceStateManager.removeDevice(mac)
    }

    // ============================================================================================
    // 스캔
    // ============================================================================================

    @MainThread
    @RequiresPermission(
        anyOf = [
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ]
    )
    fun startScan(
        scanTimeSeconds: Int = 3,
        onFound: (mac: String, name: String?, rssi: Int?) -> Unit
    ) {
        requireInit()

        val validScanTime = scanTimeSeconds.coerceIn(1, 300)
        if (scanTimeSeconds != validScanTime) {
            Log.w("Facade", "Scan time adjusted: $scanTimeSeconds → $validScanTime seconds")
        }

        scan.start(appContext) { mac, name, rssi ->
            if (mac.isNotBlank()) {
                if (!isDeviceAllowed(mac, name, rssi)) {
                    Log.d("Facade", "Scan filtered: $mac ($name)")
                    return@start
                }

                if (name != null) {
                    lastSeenName[mac] = name
                } else {
                    lastSeenName[mac] = "Unknown"
                }

                if (rssi != null) {
                    lastSeenRssi[mac] = rssi
                } else {
                    lastSeenRssi[mac] = -100
                }

                deviceStateManager.updateDeviceName(mac, name)
                deviceStateManager.updateDeviceRssi(mac, rssi)

                onFound(mac, name, rssi)
            }
        }

        scope.launch {
            kotlinx.coroutines.delay(validScanTime * 1000L)
            stopScan()
            Log.d("Facade", "Scan stopped after $validScanTime seconds")
        }
    }

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        requireInit()
        scan.stop(appContext)
    }

    // ============================================================================================
    // 연결 / 해제
    // ============================================================================================

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(mac: String, onConnected: () -> Unit, onFailed: (Throwable) -> Unit) {
        requireInit()

        sessions[mac]?.let {
            onConnected()
            return
        }

        val deviceName = lastSeenName[mac]
        val deviceRssi = lastSeenRssi[mac]

        if (!isDeviceAllowed(mac, deviceName, deviceRssi)) {
            onFailed(IllegalArgumentException("Device not allowed by filter: $deviceName"))
            return
        }

        deviceStateManager.updateConnectionState(mac, InternalConnectionState.Connecting())


        val gatt = GattClient(appContext)

        gatt.setConnectionStateListener { address, newState, gattStatus ->
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    deviceStateManager.updateConnectionState(
                        address,
                        InternalConnectionState.Connected()
                    )
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val reason = parseDisconnectReason(gattStatus)
                    deviceStateManager.updateConnectionState(
                        address,
                        InternalConnectionState.Disconnected(reason)
                    )
                    removeSession(address)
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    deviceStateManager.updateConnectionState(
                        address,
                        InternalConnectionState.Connecting()
                    )
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    deviceStateManager.updateConnectionState(
                        address,
                        InternalConnectionState.Disconnecting()
                    )
                }
            }
        }

        gatt.connect(
            address = mac,
            onConnected = {
                val led = LedControlManager(gatt)
                val deviceInfo = DeviceInfoManager(gatt)
                sessions[mac] = Session(gatt, led, deviceInfo, null)

                deviceStateManager.updateConnectionState(
                    mac,
                    InternalConnectionState.Connected()
                )

                scope.launch {
                    try {
                        val info = deviceInfo.readAllInfo()
                        deviceStateManager.updateDeviceInfo(mac, info)
                    } catch (e: Exception) {
                        // 실패해도 연결은 유지
                    }
                }

                onConnected()
            },
            onFailed = { t ->
                runCatching { gatt.disconnect() }

                deviceStateManager.updateConnectionState(
                    mac,
                    InternalConnectionState.Disconnected(InternalDisconnectReason.GATT_ERROR)
                )

                onFailed(t)
            }
        )
    }

    private fun parseDisconnectReason(gattStatus: Int): InternalDisconnectReason {
        return when (gattStatus) {
            0 -> InternalDisconnectReason.USER_REQUESTED
            8 -> InternalDisconnectReason.DEVICE_POWERED_OFF
            19 -> InternalDisconnectReason.DEVICE_POWERED_OFF
            22 -> InternalDisconnectReason.TIMEOUT
            62 -> InternalDisconnectReason.OUT_OF_RANGE
            133 -> InternalDisconnectReason.GATT_ERROR
            else -> InternalDisconnectReason.UNKNOWN
        }
    }

    @SuppressLint("MissingPermission")
    fun restoreSystemConnectedDevices() {
        requireInit()

        val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE)
                as? BluetoothManager ?: return

        val systemConnectedDevices = try {
            bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        } catch (e: SecurityException) {
            Log.w("Facade", "Permission denied: ${e.message}")
            return
        } catch (e: Exception) {
            Log.e("Facade", "Error: ${e.message}")
            return
        }

        if (systemConnectedDevices.isEmpty()) {
            Log.d("Facade", "No system-level connected devices found")
            return
        }

        Log.d("Facade", "System connected devices: ${systemConnectedDevices.size}")

        systemConnectedDevices.forEach { bluetoothDevice ->
            val mac  = bluetoothDevice.address
            val name = runCatching { bluetoothDevice.name }.getOrNull()

            if (!isDeviceAllowed(mac, name, null)) {
                Log.d("Facade", "Filtered: $mac ($name)")
                return@forEach
            }

            if (sessions.containsKey(mac)) {
                Log.d("Facade", "Already has session: $mac")
                return@forEach
            }

            if (name != null) lastSeenName[mac] = name

            Log.d("Facade", "Restoring: $mac ($name)")
            connect(
                mac         = mac,
                onConnected = { Log.d("Facade", "Restored: $mac") },
                onFailed    = { e -> Log.w("Facade", "Restore failed: $mac → ${e.message}") }
            )
        }
    }

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect(mac: String) {
        requireInit()
        removeSession(mac)

        deviceStateManager.updateConnectionState(
            mac,
            InternalConnectionState.Disconnected(InternalDisconnectReason.USER_REQUESTED)
        )
    }

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectAll() {
        requireInit()
        sessions.keys.toList().forEach { disconnect(it) }
    }

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun shutdown() {
        requireInit()
        disconnectAll()
        deviceStateManager.clearAll()
        scope.cancel()
    }

    // ============================================================================================
    // Device State
    // ============================================================================================

    fun getDeviceStateEvents(): SharedFlow<InternalDeviceStateEvent> {
        requireInit()
        return deviceStateManager.internalDeviceStateEvents
    }

    /**
     * Returns filtered device states flow.
     */
    fun getInternalDeviceStates(): Flow<Map<String, InternalDeviceState>> {
        requireInit()
        return deviceStateManager.deviceStates.map { states ->
            states.filter { (mac, state) ->
                val name = state.deviceInfo?.deviceName
                val rssi = state.deviceInfo?.rssi
                isDeviceAllowed(mac, name, rssi)
            }
        }
    }

    /**
     * Returns filtered connection states flow.
     */
    fun getInternalConnectionStates(): Flow<Map<String, InternalConnectionState>> {
        requireInit()
        return deviceStateManager.connectionStates.map { states ->
            states.filter { (mac, _) ->
                val deviceState = deviceStateManager.deviceStates.value[mac]
                val name = deviceState?.deviceInfo?.deviceName
                val rssi = deviceState?.deviceInfo?.rssi
                isDeviceAllowed(mac, name, rssi)
            }
        }
    }




    // ============================================================================================
    // 디바이스 정보 읽기
    // ============================================================================================

    /**
     * Returns cached device info for a specific device.
     */
    fun getInternalDeviceInfo(mac: String): InternalDeviceInfo? {
        requireInit()

        val deviceState = deviceStateManager.deviceStates.value[mac]
        val name = deviceState?.deviceInfo?.deviceName
        val rssi = deviceState?.deviceInfo?.rssi

        if (!isDeviceAllowed(mac, name, rssi)) {
            return null
        }

        return deviceState?.deviceInfo
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readDeviceName(mac: String, onResult: (Result<String>) -> Unit): Boolean {
        requireInit()
        if (!isConnected(mac)) return false
        scope.launch {
            val result = requireSession(mac).deviceInfo.readDeviceName()
            onResult(result)
        }
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readModelNumber(mac: String, onResult: (Result<String>) -> Unit): Boolean {
        requireInit()
        if (!isConnected(mac)) return false
        scope.launch {
            val result = requireSession(mac).deviceInfo.readModelNumber()
            onResult(result)
        }
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readFirmwareRevision(mac: String, onResult: (Result<String>) -> Unit): Boolean {
        requireInit()
        if (!isConnected(mac)) return false
        scope.launch {
            val result = requireSession(mac).deviceInfo.readFirmwareRevision()
            onResult(result)
        }
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readManufacturer(mac: String, onResult: (Result<String>) -> Unit): Boolean {
        requireInit()
        if (!isConnected(mac)) return false
        scope.launch {
            val result = requireSession(mac).deviceInfo.readManufacturerName()
            onResult(result)
        }
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readMacAddress(mac: String, onResult: (Result<String>) -> Unit): Boolean {
        requireInit()
        if (!isConnected(mac)) return false
        scope.launch {
            val result = requireSession(mac).deviceInfo.readMacAddress()
            onResult(result)
        }
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readBattery(mac: String, onResult: (Result<Int>) -> Unit): Boolean {
        requireInit()
        if (!isConnected(mac)) return false
        scope.launch {
            val result = requireSession(mac).deviceInfo.readBatteryLevel()
            onResult(result)
        }
        return true
    }

    // ============================================================================================
    // MTU
    // ============================================================================================

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestMtu(mac: String, mtu: Int, onResult: (Result<Int>) -> Unit): Boolean {
        requireInit()
        return requireSession(mac).gatt.requestMtu(mtu, onResult)
    }

    // ============================================================================================
    // LED / 이펙트 전송
    // ============================================================================================

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendColorTo(mac: String, packet4: ByteArray) {
        requireInit()
        require(packet4.size == 4) { "Color packet must be 4 bytes [R,G,B,transition]" }
        if (!isConnected(mac)) error("Not connected: $mac")
        requireSession(mac).led.sendColorPacket(packet4)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendEffectTo(mac: String, bytes20: ByteArray) {
        requireInit()
        require(bytes20.size == 20) { "Effect payload must be 20 bytes" }
        if (!isConnected(mac)) error("Not connected: $mac")
        requireSession(mac).led.sendEffectPayload(bytes20)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun playEntries(mac: String, frames: List<Pair<Long, ByteArray>>) {
        requireInit()
        if (!isConnected(mac)) error("Not connected: $mac")
        requireSession(mac).led.play(frames)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendColorPacket(packet4: ByteArray) {
        requireInit()
        require(packet4.size == 4) { "Color packet must be 4 bytes [R,G,B,transition]" }
        sessions.keys.forEach { m -> runCatching { sendColorTo(m, packet4) } }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendEffectPayload(bytes20: ByteArray) {
        requireInit()
        require(bytes20.size == 20) { "Effect payload must be 20 bytes" }
        sessions.keys.forEach { m -> runCatching { sendEffectTo(m, bytes20) } }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun playAllEntries(frames: List<Pair<Long, ByteArray>>) {
        requireInit()
        sessions.keys.forEach { m -> runCatching { playEntries(m, frames) } }
    }

    // ============================================================================================
    // 타임라인 재생 API (기존 Facade.kt에 추가)
    // ============================================================================================

    /**
     * 타임라인을 로드합니다.
     *
     * @param mac 대상 디바이스 MAC 주소
     * @param frames 타임라인 엔트리 [(timestampMs, 20B payload), ...]
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun loadTimeline(mac: String, frames: List<Pair<Long, ByteArray>>) {
        requireInit()
        if (!isConnected(mac)) error("Not connected: $mac")
        requireSession(mac).led.loadTimeline(frames)
    }

    /**
     * 현재 음악 재생 위치를 업데이트합니다.
     *
     * 주기적으로 호출되어야 하며 (권장: 100ms), SDK는 내부적으로
     * 각 이펙트를 정확한 타이밍에 전송합니다.
     *
     * @param mac 대상 디바이스 MAC 주소
     * @param currentPositionMs 현재 음악 재생 위치 (밀리초)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun updatePlaybackPosition(mac: String, currentPositionMs: Long) {
        requireInit()
        if (!isConnected(mac)) return
        requireSession(mac).led.updatePlaybackPosition(currentPositionMs)
    }

    /**
     * 이펙트 전송을 일시정지합니다.
     *
     * @param mac 대상 디바이스 MAC 주소
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun pauseEffects(mac: String) {
        requireInit()
        if (!isConnected(mac)) return
        requireSession(mac).led.pauseEffects()
    }

    /**
     * 이펙트 전송을 재개합니다.
     *
     * 내부적으로 syncIndex가 자동 증가하여 재동기화가 처리됩니다.
     *
     * @param mac 대상 디바이스 MAC 주소
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun resumeEffects(mac: String) {
        requireInit()
        if (!isConnected(mac)) return
        requireSession(mac).led.resumeEffects()
    }

    /**
     * 타임라인 재생을 완전히 중단합니다.
     *
     * @param mac 대상 디바이스 MAC 주소
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stopTimeline(mac: String) {
        requireInit()
        if (!isConnected(mac)) return
        requireSession(mac).led.stopTimeline()
    }

    /**
     * 타임라인 재생 상태를 조회합니다.
     *
     * @param mac 대상 디바이스 MAC 주소
     * @return true if timeline is loaded and effects are enabled
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isTimelinePlaying(mac: String): Boolean {
        requireInit()
        if (!isConnected(mac)) return false
        return requireSession(mac).led.isTimelinePlaying()
    }

    // ============================================================================================
    // OTA
    // ============================================================================================

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startOta(
        mac: String,
        firmware: ByteArray,
        preferredMtu: Int = 247,
        startOpcodes: ByteArray? = null,
        onProgress: (Int) -> Unit,
        onResult: (Result<Unit>) -> Unit
    ) {
        requireInit()
        if (!isConnected(mac)) error("Not connected: $mac")
        val s = requireSession(mac)
        val otaMgr = s.ota ?: OtaManager(s.gatt).also { sessions[mac] = s.copy(ota = it) }
        otaMgr.start(
            serviceUuid = UuidConstants.OTA_SERVICE,
            dataCharUuid = UuidConstants.OTA_DATA,
            firmware = firmware,
            preferredMtu = preferredMtu,
            startOpcodes = startOpcodes,
            onProgress = onProgress,
            onResult = onResult
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun abortOta(mac: String) {
        requireInit()
        sessions[mac]?.ota?.abort()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun otaState(mac: String): Flow<Int> {
        requireInit()
        val s = requireSession(mac)
        val stateFlow: StateFlow<OtaState> =
            s.ota?.state ?: error("OTA Manager not initialized for $mac")
        return stateFlow.map { it.ordinal }
    }

    // ============================================================================================
    // 연결 상태 조회 유틸
    // ============================================================================================

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectedList(): List<Triple<String, String?, Int?>> {
        requireInit()
        return sessions.keys
            .filter { mac ->
                val name = lastSeenName[mac]
                val rssi = lastSeenRssi[mac]
                isDeviceAllowed(mac, name, rssi)
            }
            .map { mac ->
                Triple(
                    mac,
                    lastSeenName[mac],
                    lastSeenRssi[mac]
                )
            }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectedCount(): Int {
        requireInit()
        return connectedList().size
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun bondedList(): List<Pair<String, String?>> {
        requireInit()
        var result: List<Pair<String, String?>> = emptyList()
        bond.listBonded(appContext) { res ->
            result = res.getOrElse { emptyList() }
                .filter { (mac, name) ->
                    isDeviceAllowed(mac, name, null)
                }
        }
        return result
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun bondedCount(): Int {
        requireInit()
        return bondedList().size
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isConnected(mac: String): Boolean {
        requireInit()
        val session = sessions[mac] ?: return false
        return runCatching { session.gatt.isConnected() }.getOrDefault(false)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isBonded(mac: String): Boolean {
        requireInit()
        return bondedList().any { (addr, _) -> addr == mac }
    }

    // ============================================================================================
    // EFX
    // ============================================================================================

    @JvmStatic fun efxMagic(): String = EfxBinary.MAGIC
    @JvmStatic fun efxVersion(): Int = EfxBinary.VERSION
    @JvmStatic fun efxReserved(): ByteArray = EfxBinary.RESERVED3

    @JvmStatic
    fun efxEncode(musicId: Int, frames: List<Pair<Long, ByteArray>>): ByteArray {
        return EfxBinary.encode(
            magic = EfxBinary.MAGIC,
            version = EfxBinary.VERSION,
            reserved3 = EfxBinary.RESERVED3,
            musicId = musicId,
            frames = frames
        )
    }

    @JvmStatic
    fun efxInspect(bytes: ByteArray): Map<String, Long> {
        val p = EfxBinary.decode(bytes)
        return mapOf(
            "musicId" to (p.musicId.toLong() and 0xFFFF_FFFFL),
            "entryCount" to (p.entryCount.toLong() and 0xFFFF_FFFFL)
        )
    }

    @JvmStatic
    fun efxDecode(bytes: ByteArray): DecodedEfx {
        val p = EfxBinary.decode(bytes)
        return DecodedEfx(
            magic = p.magic,
            version = p.version,
            reserved3 = p.reserved3,
            musicId = p.musicId,
            entryCount = p.entryCount,
            frames = p.frames
        )
    }

    data class DecodedEfx(
        val magic: String,
        val version: Int,
        val reserved3: ByteArray,
        val musicId: Int,
        val entryCount: Int,
        val frames: List<Pair<Long, ByteArray>>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecodedEfx) return false
            return magic == other.magic &&
                    version == other.version &&
                    reserved3.contentEquals(other.reserved3) &&
                    musicId == other.musicId &&
                    entryCount == other.entryCount &&
                    frames == other.frames
        }

        override fun hashCode(): Int {
            var result = magic.hashCode()
            result = 31 * result + version
            result = 31 * result + reserved3.contentHashCode()
            result = 31 * result + musicId
            result = 31 * result + entryCount
            result = 31 * result + frames.hashCode()
            return result
        }

        override fun toString(): String {
            return "DecodedEfx(magic=$magic, version=$version, reserved3.size=${reserved3.size}, " +
                    "musicId=$musicId, entryCount=$entryCount, frames=${frames.size})"
        }
    }

    // ============================================================================================
    // MusicId
    // ============================================================================================

    @JvmStatic
    fun musicIdFromFile(file: java.io.File): Int =
        MusicIdProvider.fromFile(file)

    @JvmStatic
    fun musicIdFromStream(stream: java.io.InputStream, filenameHint: String? = null): Int =
        MusicIdProvider.fromStream(stream, filenameHint)

    @JvmStatic
    fun musicIdFromUri(context: Context, uri: android.net.Uri): Int =
        MusicIdProvider.fromUri(context, uri)

    // ============================================================================================
    // Events
    // ============================================================================================

    fun eventEnable() {
        requireInit()
        if (!eventInitialized) {
            EventRouter.initialize(appContext)
            eventInitialized = true
        }
        EventRouter.enable()
    }

    fun eventDisable() {
        requireInit()
        EventRouter.disable()
    }

    fun eventOnNotificationListenerConnected() {
        requireInit()
        EventRouter.onNotificationListenerConnected()
    }

    fun eventOnNotificationListenerDisconnected() {
        requireInit()
        EventRouter.onNotificationListenerDisconnected()
    }

    fun eventOnNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        requireInit()
        EventRouter.onNotificationPosted(sbn)
    }

    fun eventOnNotificationRemoved(sbn: android.service.notification.StatusBarNotification) {
        requireInit()
        EventRouter.onNotificationRemoved(sbn)
    }

    fun eventSetGlobalRulesInternal(rules: List<InternalRule>) {
        requireInit()
        GlobalEventRegistry.set(rules)
    }

    fun eventClearGlobalRulesInternal() {
        requireInit()
        GlobalEventRegistry.clear()
    }

    fun eventSetDeviceRulesInternal(mac: String, rules: List<InternalRule>) {
        requireInit()
        DeviceEventRegistry.set(mac, rules)
    }

    fun eventClearDeviceRulesInternal(mac: String) {
        requireInit()
        DeviceEventRegistry.clear(mac)
    }

    fun eventGetGlobalRulesInternal(): List<InternalRule> {
        requireInit()
        return GlobalEventRegistry.get()
    }

    fun eventGetDeviceRulesInternal(mac: String): List<InternalRule> {
        requireInit()
        return DeviceEventRegistry.get(mac)
    }

    fun eventGetAllDeviceRulesInternal(): Map<String, List<InternalRule>> {
        requireInit()
        return DeviceEventRegistry.getAll()
    }

    // ============================================================================================
    // Bond
    // ============================================================================================

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun ensureBond(mac: String, onDone: () -> Unit, onFailed: (Throwable) -> Unit) {
        requireInit()
        bond.ensureBond(appContext, mac, onDone, onFailed)
    }

    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun removeBond(mac: String, onResult: (Result<Unit>) -> Unit) {
        requireInit()
        bond.removeBond(appContext, mac, onResult)
    }
}