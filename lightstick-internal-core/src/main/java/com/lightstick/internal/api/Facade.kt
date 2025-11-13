package com.lightstick.internal.api

import android.Manifest
import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import com.lightstick.internal.ble.*
import com.lightstick.internal.ble.ota.OtaManager
import com.lightstick.internal.ble.ota.OtaState
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

/**
 * 내부 Facade.
 *
 * 이 객체는 내부 모듈들(BLE 스택, EFX 코덱, 이벤트 라우팅)을 **단일 진입점**으로 묶어
 * 공개 모듈(예: com.lightstick.ota.OtaManager 등)에서 간단히 호출할 수 있도록 도와준다.
 *
 * 설계 원칙:
 * - 외부(공개) DTO에 직접 의존하지 않는다. (내부 타입/모듈만 사용)
 * - 다중 디바이스를 고려하여 세션(Session)을 주소별로 관리한다.
 * - 자원 수명은 connect()/disconnect()를 기준으로 명확히 관리한다.
 * - OTA 상태는 내부 enum(OtaState)을 외부로 직접 노출하지 않고 **정수(ordinal)** 로 변환해 제공한다.
 */
object Facade {

    // ============================================================================================
    // 초기화 / 컨텍스트
    // ============================================================================================

    /** 애플리케이션 컨텍스트 (initialize() 호출로 설정) */
    private lateinit var appContext: Context

    /** 내부 작업용 CoroutineScope (IO 디스패처 + SupervisorJob) */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 이벤트 라우터 초기화 여부 */
    @Volatile private var eventInitialized = false

    /** initialize() 선행 보장용 체크 */
    private fun requireInit() {
        check(::appContext.isInitialized) { "Facade.initialize(context) must be called first." }
    }

    /**
     * Facade 초기화.
     * - ScanManager / BondManager 인스턴스를 준비한다.
     * - 내부 이벤트 라우터(EventRouter)를 초기화한다.
     */
    @MainThread
    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        scan = ScanManager()
        bond = BondManager()

        // 내부 이벤트 엔진 부트스트랩 (공개 DTO 의존 없음)
        EventRouter.initialize(appContext)
        eventInitialized = true
    }

    // ============================================================================================
    // 매니저 (stateless)
    // ============================================================================================

    /** BLE 스캔 관리자 */
    private lateinit var scan: ScanManager

    /** 시스템 Bond(페어링) 관리자 */
    private lateinit var bond: BondManager

    // ============================================================================================
    // 세션 (멀티 디바이스 관리)
    // ============================================================================================

    /**
     * 주소별 연결 세션.
     * - gatt: 연결 및 GATT 입출력
     * - led: LED 제어 매니저
     * - deviceInfo: 디바이스 정보 읽기 매니저
     * - ota: OTA 전송 매니저 (필요 시 생성)
     */
    private data class Session(
        val gatt: GattClient,
        val led: LedControlManager,
        val deviceInfo: DeviceInfoManager,
        val ota: OtaManager?
    )

    /** 연결된 세션(주소 → 세션) */
    private val sessions: MutableMap<String, Session> = ConcurrentHashMap()

    /** 최근 스캔에서 관측한 이름/신호강도 캐시 */
    private val lastSeenName = ConcurrentHashMap<String, String?>()
    private val lastSeenRssi = ConcurrentHashMap<String, Int?>()

    // ============================================================================================
    // 스캔
    // ============================================================================================

    /**
     * BLE 스캔 시작.
     * @param onFound 스캔 콜백 (mac, name, rssi)
     */
    @MainThread
    @RequiresPermission(
        anyOf = [
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ]
    )
    fun startScan(onFound: (mac: String, name: String?, rssi: Int?) -> Unit) {
        requireInit()
        scan.start(appContext) { mac, name, rssi ->
            lastSeenName[mac] = name
            lastSeenRssi[mac] = rssi
            onFound(mac, name, rssi)
        }
    }

    /** BLE 스캔 중지. */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        requireInit()
        scan.stop(appContext)
    }

    // ============================================================================================
    // 연결 / 해제
    // ============================================================================================

    /**
     * 디바이스에 연결.
     * - 연결 성공 시 세션을 생성하고 콜백을 호출한다.
     * - 실패 시 gatt를 정리하고 실패 콜백을 호출한다.
     */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(mac: String, onConnected: () -> Unit, onFailed: (Throwable) -> Unit) {
        requireInit()
        // 이미 세션이 있으면 즉시 성공 콜백
        sessions[mac]?.let { onConnected(); return }

        val gatt = GattClient(appContext)
        gatt.connect(
            address = mac,
            onConnected = {
                // 연결 성공: 서브 매니저 구성
                val led = LedControlManager(gattClient = gatt)
                val deviceInfo = DeviceInfoManager(gatt)
                val ota = OtaManager(gatt) // 필요 시 바로 준비 (상태 관찰을 위해)
                sessions[mac] = Session(gatt, led, deviceInfo, ota)
                onConnected()
            },
            onFailed = { t ->
                // 연결 실패: GATT 정리
                runCatching { gatt.disconnect() }
                onFailed(t)
            }
        )
    }

    /**
     * 디바이스 연결 해제.
     * - OTA/LED/GATT 순서로 자원을 안전하게 정리한다.
     */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect(mac: String) {
        requireInit()
        sessions.remove(mac)?.let { s ->
            runCatching { s.ota?.close() }
            runCatching { s.led.close() }
            runCatching { s.gatt.disconnect() }
        }
    }

    /** 모든 디바이스 연결 해제. */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectAll() {
        requireInit()
        sessions.keys.toList().forEach { disconnect(it) }
    }

    /**
     * Facade 종료.
     * - 모든 연결을 해제하고 내부 스코프를 취소한다.
     * - 스캔 캐시를 정리한다.
     */
    @MainThread
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun shutdown() {
        requireInit()
        disconnectAll()
        scope.cancel()
        lastSeenName.clear()
        lastSeenRssi.clear()
    }

    /** 연결된 세션을 요구 (없으면 예외) */
    private fun requireSession(mac: String): Session =
        sessions[mac] ?: error("Not connected: $mac")

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


    // ============================================================================================
    // LED / 이펙트 전송 (단일 대상)
    // ============================================================================================

    /**
     * 단일 디바이스에 4바이트 색상 패킷 전송 [R, G, B, transition].
     *
     * 연결 가드:
     * - 세션 존재 + GATT 실제 연결 확인 후 전송.
     *
     * @throws IllegalArgumentException 크기 검증 실패 시
     * @throws IllegalStateException 연결되지 않은 경우
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendColorTo(mac: String, packet4: ByteArray) {
        requireInit()
        require(packet4.size == 4) { "Color packet must be 4 bytes [R,G,B,transition]" }
        if (!isConnected(mac)) error("Not connected: $mac")
        requireSession(mac).led.sendColorPacket(packet4)
    }

    /**
     * 단일 디바이스에 16바이트 이펙트 페이로드 전송.
     *
     * 연결 가드:
     * - 세션 존재 + GATT 실제 연결 확인 후 전송.
     *
     * @throws IllegalArgumentException 크기 검증 실패 시
     * @throws IllegalStateException 연결되지 않은 경우
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendEffectTo(mac: String, bytes16: ByteArray) {
        requireInit()
        require(bytes16.size == 16) { "Effect payload must be 16 bytes" }
        if (!isConnected(mac)) error("Not connected: $mac")
        requireSession(mac).led.sendEffectPayload(bytes16)
    }

    /**
     * 단일 디바이스에 타임라인 프레임들을 재생.
     * frames: (timestampMs, payload16)
     *
     * 연결 가드:
     * - 세션 존재 + GATT 실제 연결 확인 후 재생.
     *
     * @throws IllegalStateException 연결되지 않은 경우
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun playEntries(mac: String, frames: List<Pair<Long, ByteArray>>) {
        requireInit()
        if (!isConnected(mac)) error("Not connected: $mac")
        requireSession(mac).led.play(frames)
    }

    // ============================================================================================
    // LED / 이펙트 전송 (브로드캐스트)
    // ============================================================================================

    /** 모든 연결 디바이스에 4바이트 색상 패킷 전송. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendColorPacket(packet4: ByteArray) {
        requireInit()
        require(packet4.size == 4) { "Color packet must be 4 bytes [R,G,B,transition]" }
        sessions.keys.forEach { m -> runCatching { sendColorTo(m, packet4) } }
    }

    /** 모든 연결 디바이스에 16바이트 이펙트 페이로드 전송. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendEffectPayload(bytes16: ByteArray) {
        requireInit()
        require(bytes16.size == 16) { "Effect payload must be 16 bytes" }
        sessions.keys.forEach { m -> runCatching { sendEffectTo(m, bytes16) } }
    }

    /** 모든 연결 디바이스에서 동일 타임라인 재생. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun playFrames(entries: List<Pair<Long, ByteArray>>) {
        requireInit()
        sessions.keys.forEach { m -> runCatching { playEntries(m, entries) } }
    }

    // ============================================================================================
    // MTU / Reads
    // ============================================================================================

    /** MTU 요청 (비동기 콜백) */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestMtu(mac: String, preferred: Int, onResult: (Result<Int>) -> Unit) {
        requireInit()
        requireSession(mac).gatt.requestMtu(preferred, onResult)
    }

    /** 디바이스 이름 읽기 */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readDeviceName(mac: String, onResult: (Result<String>) -> Unit) {
        requireInit()
        val s = requireSession(mac)
        scope.launch { onResult(s.deviceInfo.readDeviceName(mac)) }
    }

    /** 모델명 읽기 */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readModelNumber(mac: String, onResult: (Result<String>) -> Unit) {
        requireInit()
        val s = requireSession(mac)
        scope.launch { onResult(s.deviceInfo.readModelNumber(mac)) }
    }

    /** 펌웨어 버전 읽기 */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readFirmwareRevision(mac: String, onResult: (Result<String>) -> Unit) {
        requireInit()
        val s = requireSession(mac)
        scope.launch { onResult(s.deviceInfo.readFirmwareRevision(mac)) }
    }

    /** 제조사명 읽기 */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readManufacturer(mac: String, onResult: (Result<String>) -> Unit) {
        requireInit()
        val s = requireSession(mac)
        scope.launch { onResult(s.deviceInfo.readManufacturerName(mac)) }
    }

    /** 맥어드레스 읽기 (디바이스 정보 서비스 기반) */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readMacAddress(mac: String, onResult: (Result<String>) -> Unit) {
        requireInit()
        val s = requireSession(mac)
        scope.launch { onResult(s.deviceInfo.readMacAddress(mac)) }
    }

    /** 배터리 레벨 읽기 */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readBattery(mac: String, onResult: (Result<Int>) -> Unit) {
        requireInit()
        val s = requireSession(mac)
        scope.launch { onResult(s.deviceInfo.readBatteryLevel(mac)) }
    }

    // ============================================================================================
    // OTA
    // ============================================================================================

    /**
     * OTA 시작 (Telink 계열 시퀀스 준수).
     * - 내부 OtaManager를 세션에 보관하여, 상태 관찰/중단이 가능하도록 한다.
     *
     * 연결 가드:
     * - 세션 존재 + GATT 실제 연결 확인 후 시작.
     */
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

    /** 진행 중 OTA 중단. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun abortOta(mac: String) {
        requireInit()
        sessions[mac]?.ota?.abort()
    }

    /**
     * OTA 상태 관찰(Flow) - **정수(ordinal)로 노출**.
     *
     * 주의:
     * - 내부 enum(OtaState)을 public API에 직접 노출하지 않기 위해 **Int** 로 변환한다.
     * - 공개 모듈에서는 해당 정수를 자체 공개 enum(OtaStatus)의 values()[ordinal] 형태로 매핑해 사용한다.
     *
     * 예)
     *  public 모듈:
     *    Facade.otaState(mac).map { ordinal -> OtaStatus.values().getOrElse(ordinal) { OtaStatus.ERROR } }
     */
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

    /** 연결 디바이스 목록 (mac, name, rssi) */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectedList(): List<Triple<String, String?, Int?>> {
        requireInit()
        return sessions.keys.map { mac -> Triple(mac, lastSeenName[mac], lastSeenRssi[mac]) }
    }

    /** 연결 디바이스 수 */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectedCount(): Int {
        requireInit()
        return sessions.size
    }

    /** 시스템 Bonded(페어링 완료) 목록 (mac, name) */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun bondedList(): List<Pair<String, String?>> {
        requireInit()
        var result: List<Pair<String, String?>> = emptyList()
        bond.listBonded(appContext) { res -> result = res.getOrElse { emptyList() } }
        return result
    }

    /** Bonded 디바이스 수 */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun bondedCount(): Int {
        requireInit()
        return bondedList().size
    }

    /** 특정 mac 연결 여부 (실제 GATT 연결 상태 포함) */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isConnected(mac: String): Boolean {
        requireInit()
        val session = sessions[mac] ?: return false
        return runCatching { session.gatt.isConnected() }.getOrDefault(false)
    }

    /** 특정 mac Bond 여부 */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isBonded(mac: String): Boolean {
        requireInit()
        return bondedList().any { (addr, _) -> addr == mac }
    }

    // ============================================================================================
    // EFX (codec bridge -> EfxBinary)
    // ============================================================================================

    /** EFX 헤더: 매직 문자열 */
    @JvmStatic fun efxMagic(): String = EfxBinary.MAGIC

    /** EFX 헤더: 버전 */
    @JvmStatic fun efxVersion(): Int = EfxBinary.VERSION

    /** EFX 헤더: 예약 3바이트(프로토콜 규약에 맞춰 유지) */
    @JvmStatic fun efxReserved(): ByteArray = EfxBinary.RESERVED3

    /**
     * (musicId, frames)로부터 EFX 바이너리를 생성.
     * frames: (timestampMs, payload16)
     */
    @JvmStatic
    fun efxSerializeFromEntries(
        musicId: Int,
        frames: List<Pair<Long, ByteArray>>
    ): ByteArray {
        return EfxBinary.encode(
            magic = EfxBinary.MAGIC,
            version = EfxBinary.VERSION,
            reserved3 = EfxBinary.RESERVED3,
            musicId = musicId,
            frames = frames
        )
    }

    /**
     * EFX 바이너리 요약 정보 조회 (헤더 기반).
     * @return map: musicId, entryCount
     */
    @JvmStatic
    fun efxInspect(bytes: ByteArray): Map<String, Long> {
        val p = EfxBinary.decode(bytes)
        return mapOf(
            "musicId" to (p.musicId.toLong() and 0xFFFF_FFFFL),
            "entryCount" to (p.entryCount.toLong() and 0xFFFF_FFFFL)
        )
    }

    /**
     * EFX 바이너리 완전 디코드.
     * @return 헤더 + 프레임 전체
     */
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

    /** efxDecode() 결과 DTO */
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
    // MusicId (bridge → MusicIdProvider)
    // ============================================================================================

    /** 파일로부터 musicId 생성 */
    @JvmStatic
    fun musicIdFromFile(file: java.io.File): Int =
        MusicIdProvider.fromFile(file)

    /** 스트림으로부터 musicId 생성 (파일명 힌트 선택) */
    @JvmStatic
    fun musicIdFromStream(stream: java.io.InputStream, filenameHint: String? = null): Int =
        MusicIdProvider.fromStream(stream, filenameHint)

    /** Uri로부터 musicId 생성 (안드로이드 컨텍스트 필요) */
    @JvmStatic
    fun musicIdFromUri(context: Context, uri: android.net.Uri): Int =
        MusicIdProvider.fromUri(context, uri)

    // ============================================================================================
    // Events (공개 → 내부). 중요: 여기서는 **공개 DTO에 의존하지 않는다**.
    //  - 공개 모듈에서 자체 DTO → InternalRule 로 매핑하여 넘길 것.
    // ============================================================================================

    /** 내부 이벤트 모니터 활성화(리시버 등록). */
    fun eventEnable() {
        requireInit()
        if (!eventInitialized) {
            EventRouter.initialize(appContext)
            eventInitialized = true
        }
        EventRouter.enable()
    }

    /** 내부 이벤트 모니터 비활성화(리시버 해제). */
    fun eventDisable() {
        requireInit()
        EventRouter.disable()
    }

    /** NotificationListenerService 연결 알림 전달. */
    fun eventOnNotificationListenerConnected() {
        requireInit()
        EventRouter.onNotificationListenerConnected()
    }

    /** NotificationListenerService 연결 끊김 알림 전달. */
    fun eventOnNotificationListenerDisconnected() {
        requireInit()
        EventRouter.onNotificationListenerDisconnected()
    }

    /** 게시된 알림 전달. */
    fun eventOnNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        requireInit()
        EventRouter.onNotificationPosted(sbn)
    }

    /** 제거된 알림 전달. */
    fun eventOnNotificationRemoved(sbn: android.service.notification.StatusBarNotification) {
        requireInit()
        EventRouter.onNotificationRemoved(sbn)
    }

    // ---- Rule registry APIs (내부 타입만) ------------------------------------------------------

    /** 전역 규칙 설정 */
    fun eventSetGlobalRulesInternal(rules: List<InternalRule>) {
        requireInit()
        GlobalEventRegistry.set(rules)
    }

    /** 전역 규칙 비우기 */
    fun eventClearGlobalRulesInternal() {
        requireInit()
        GlobalEventRegistry.clear()
    }

    /** 특정 디바이스 규칙 설정 */
    fun eventSetDeviceRulesInternal(mac: String, rules: List<InternalRule>) {
        requireInit()
        DeviceEventRegistry.set(mac, rules)
    }

    /** 특정 디바이스 규칙 비우기 */
    fun eventClearDeviceRulesInternal(mac: String) {
        requireInit()
        DeviceEventRegistry.clear(mac)
    }

    /** 전역 규칙 조회 */
    fun eventGetGlobalRulesInternal(): List<InternalRule> {
        requireInit()
        return GlobalEventRegistry.get()
    }

    /** 특정 디바이스 규칙 조회 */
    fun eventGetDeviceRulesInternal(mac: String): List<InternalRule> {
        requireInit()
        return DeviceEventRegistry.get(mac)
    }

    /** 모든 디바이스 규칙 맵 조회 */
    fun eventGetAllDeviceRulesInternal(): Map<String, List<InternalRule>> {
        requireInit()
        return DeviceEventRegistry.getAll()
    }
}
