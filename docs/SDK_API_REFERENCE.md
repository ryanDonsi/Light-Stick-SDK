# Light-Stick SDK API 레퍼런스 (v2.0.0)

`com.lightstick` 패키지(모듈: `lightstick`, `lightstick-internal-core`, 배포 아티팩트: `lightstick-sdk`)가 제공하는
**공개(public) Kotlin API**를 모두 정리한 문서. 순수 BLE 와이어 프로토콜은 [`BLE_PROTOCOL.md`](./BLE_PROTOCOL.md)를 참고.

---

## 0. 모듈 / 패키지 구조

| 패키지 | 내용 |
|---|---|
| `com.lightstick` | `LSBluetooth` (전역 진입점) |
| `com.lightstick.device` | `Device`, `DeviceInfo`, `DeviceState`, `ConnectionState`, `DeviceInfoResult` |
| `com.lightstick.config` | `DeviceFilter`, `InitConfig`/`InitResult`(§9 참고 — 현재 미사용) |
| `com.lightstick.types` | `Color`, `Colors`, `EffectType`, `MsgType`, `Group`, `GroupPalette`, `LSEffectPayload` |
| `com.lightstick.game` | `GameCmd`, `GameMode`, `GameLevel`, `GameResult` |
| `com.lightstick.efx` | `Efx`, `EfxHeader`, `EfxBody`, `EfxEntry`, `MusicId` |
| `com.lightstick.ota` | `OtaManager` (+ 중첩 `OtaProgress`/`OtaResult`) |
| `com.lightstick.samples` | KDoc `@sample`용 예제 모음 (런타임에서 직접 쓰는 코드 아님) |

**API 공통 원칙**
- 대부분의 메서드는 `Boolean`을 반환하며, 의미는 **"BLE 스택에 요청이 제출됨"**이지 "기기에서 실제로 성공했음"이 아니다.
- 성공/실패 데이터가 필요한 호출은 `Result<T>` 콜백으로 전달된다.
- `false` 반환 또는 `Result.failure(...)`가 유일한 실패 통지 경로 — 별도의 `onError` 콜백은 없다.
- `@RequiresPermission(BLUETOOTH_CONNECT)`가 붙은 메서드는 Android 12+ 런타임 권한이 없으면 `SecurityException`을 던진다.

---

## 1. `LSBluetooth` — 전역 진입점 (`object`)

앱 전체에서 한 번 초기화하고, 스캔/전역 조회/브로드캐스트/상태 관찰/종료를 담당한다. 기기별 제어는 `Device`를 사용한다.

### 1.1 초기화

| 메서드 | 설명 |
|---|---|
| `initialize(context, deviceFilter: DeviceFilter? = null, allowUnknownDevices: Boolean = false)` | SDK 초기화. 앱 시작 시 1회 (여러 번 호출해도 최초 1회만 적용). 내부적으로 시스템 레벨에 이미 연결된 기기 세션 복원도 시도한다. |
| `setDebugLoggingEnabled(enabled: Boolean)` | 패킷 TX/RX, 연결/서비스디스커버리/OTA 진단 로그를 `Lightstick` 태그로 ON/OFF. 기본값 `false`. 언제든 호출 가능. |

### 1.2 스캔

| 메서드 | 설명 |
|---|---|
| `startScan(scanTimeSeconds: Int = 3, onFound: (Device) -> Unit)` | BLE 스캔 시작 (1~300초, 범위 밖이면 클램프). 발견될 때마다 `onFound` 호출. |
| `stopScan()` | 스캔 중단. |

### 1.3 전역 조회

| 메서드 | 반환 |
|---|---|
| `connectedDevices(): List<Device>` | 현재 연결된 기기 목록 |
| `connectedCount(): Int` | 연결된 기기 수 |
| `bondedDevices(): List<Device>` | 시스템 페어링된 기기 목록 (rssi=null) |
| `bondedCount(): Int` | 페어링된 기기 수 |

### 1.4 상태 관찰

| 메서드 | 반환 | 설명 |
|---|---|---|
| `observeDeviceStateEvents(): SharedFlow<DeviceStateEvent>` | 연결 상태가 바뀔 때마다 1건씩 발행되는 이벤트 스트림 (`DeviceStateEvent(mac, state)`) |
| `observeDeviceStates(): StateFlow<Map<String, DeviceState>>` | mac별 통합 상태(연결상태+DeviceInfo) 스냅샷. **실제 값 변경 시에만 재발행** (v2.0.0에서 무의미한 재발행 버그 수정됨) |
| `observeConnectionStates(): StateFlow<Map<String, ConnectionState>>` | mac별 연결 상태만 |
| `getCachedDeviceInfo(mac: String): DeviceInfoResult` | 캐시된 기기 정보 스냅샷 (동기, Flow 아님) |

> ⚠️ **RSSI 관련 알려진 특성**: `DeviceState.deviceInfo.rssi`는 스캔이 계속 진행 중이면 매우 자주 바뀌는 값이라, 신호세기를 실시간으로 추적하려는 용도로 `observeDeviceStates()`를 쓰면 원치 않게 자주 재발행될 수 있다. RSSI 전용 별도 스트림은 아직 없음(논의 중) — 현재는 `observeDeviceStates()`가 유일한 경로.

### 1.5 종료

| 메서드 | 설명 |
|---|---|
| `shutdown()` | 모든 기기 연결 해제 + SDK 리소스 정리 |

---

## 2. `Device` — 기기별 API (`data class`)

```kotlin
data class Device(val mac: String, val name: String? = null, val rssi: Int? = null) : Parcelable
```
스캔 결과 또는 `Device(mac, name, rssi)`로 직접 생성해서 사용. 모든 메서드는 `this.mac`을 대상으로 동작한다.

### 2.1 연결 / 해제 / 페어링

| 메서드 | 설명 |
|---|---|
| `connect(onConnected: () -> Unit = {}, onFailed: (Throwable) -> Unit = {}, onDeviceInfo: ((DeviceInfo) -> Unit)? = null)` | 연결. `onDeviceInfo`를 넘기면 DIS/BAS/MAC 자동 읽기 완료 후 `onConnected` 직전에 1회 호출됨(항상 보장). |
| `disconnect(): Boolean` | 연결 해제 |
| `bond(onDone: (() -> Unit)? = null, onFailed: ((Throwable) -> Unit)? = null): Boolean` | 시스템 페어링 요청 |
| `unbond(onDone: (() -> Unit)? = null): Boolean` | 페어링 해제 요청 |
| `isConnected(): Boolean` | 연결 여부 |
| `isBonded(): Boolean` | 페어링 여부 |

### 2.2 LED 색상 / 이펙트 (단발)

| 메서드 | 설명 |
|---|---|
| `sendColor(color: Color, transition: Int): Boolean` | FF01 4바이트 컬러 패킷 전송 |
| `sendEffect(payload: LSEffectPayload): Boolean` | FF02 20바이트 이펙트 전송. **그룹 컨트롤도 이 메서드 하나** — `payload.groupMask`만 다르게 주면 됨 (별도 그룹 API 없음). |

### 2.3 이펙트 재생 — `playEffects`/`stopEffects` (자체 시계, 원샷)

| 메서드 | 설명 |
|---|---|
| `playEffects(frames: List<Pair<Long, ByteArray>>): Boolean` | 타임스탬프가 찍힌 프레임 시퀀스를 SDK 자체 시계(나노초 정밀 `delay()`)로 1회 재생. 외부 동기화 불필요. |
| `stopEffects(): Boolean` | 진행 중인 `playEffects` 취소 |

특징: 나노초 정밀 타이밍 · 드랍 허용(backpressure 시 coalesce 가능) · effectIndex 안 찍음 · pause 불가.

### 2.4 타임라인 재생 — `playTimeline`/`stopTimeline` (외부 동기화·pause 가능)

| 메서드 | 설명 |
|---|---|
| `playTimeline(frames: List<Pair<Long, ByteArray>>): Boolean` | 타임라인 시작. `updatePlaybackPosition()`을 안 부르면 로드 시점부터 자체 시계로 free-run. |
| `updatePlaybackPosition(currentPositionMs: Long): Boolean` | 외부 음악 재생 위치와 동기화 (권장 100ms 주기 호출). 뒤로 1초 이상/앞으로 10초 이상 점프 시 자동 seek 감지. |
| `pauseTimeline(): Boolean` | 전송 일시정지 (내부 시계는 계속 흐름 — 정지 중 지난 프레임은 재생되지 않고 스킵됨) |
| `resumeTimeline(): Boolean` | 재개 (effectIndex 자동 증가로 기기 재동기화) |
| `stopTimeline(): Boolean` | 타임라인 중단 + 데이터 클리어. 재개하려면 `playTimeline()` 재호출 |
| `isTimelinePlaying(): Boolean` | 로드됨 + 전송 활성 상태인지 조회 |

특징: 10ms tick 배치 디스패치 · 드랍 없음(coalesce 안 함, 순서·개수 보장) · 세션마다 effectIndex 찍음(기기 dedup) · pause/resume 지원.

> `playEffects`/`stopEffects`와 `playTimeline`/`stopTimeline`은 서로 다른 내부 재생 잡(playJob/monitorJob)이라 상호 취소하지 않는다 — 동시에 호출하면 두 메커니즘이 FF02에 동시에 쓸 수 있으므로 앱에서 하나만 쓰거나 명시적으로 순서를 맞춰야 한다.

### 2.5 MTU

| 메서드 | 설명 |
|---|---|
| `requestMtu(preferred: Int): Boolean` | MTU 협상 요청. 결과는 별도 옵저버 필요(현재 `Device` 레벨에 직접적인 MTU 결과 콜백 없음 — 내부적으로만 로깅). |

### 2.6 기기 정보 (DIS / BAS / MAC)

| 메서드 | 설명 |
|---|---|
| `readDeviceName(onResult: (Result<String>) -> Unit): Boolean` | GAP 0x2A00 |
| `readModelNumber(onResult: (Result<String>) -> Unit): Boolean` | DIS 0x2A24 |
| `readFirmwareRevision(onResult: (Result<String>) -> Unit): Boolean` | DIS 0x2A26 |
| `readManufacturer(onResult: (Result<String>) -> Unit): Boolean` | DIS 0x2A29 |
| `readMacAddress(onResult: (Result<String>) -> Unit): Boolean` | 커스텀 MAC 캐릭터리스틱 |
| `supportsBattery(): Boolean` | BAS 0x2A19 지원 여부 (연결 후 서비스 디스커버리 완료 후 호출) |
| `readBattery(onResult: (Result<Int>) -> Unit): Boolean` | 배터리 잔량 (0..100) 단건 조회 — DIS는 건드리지 않음 |
| `fetchDeviceInfo(onResult: (DeviceInfo) -> Unit): Boolean` | 위 4개 DIS 필드를 병렬로 읽어 취합한 콜백 1회 |

> 배터리만 주기적으로 갱신하고 싶다면 `readBattery`만 앱 쪽 타이머로 반복 호출하면 된다 — SDK가 자동으로 폴링하지 않는다.

### 2.7 OTA

| 메서드 | 설명 |
|---|---|
| `startOta(firmware: ByteArray, onProgress: ((Int) -> Unit)? = null, onResult: ((Result<Unit>) -> Unit)? = null): Boolean` | OTA 시작 (내부적으로 `com.lightstick.ota.OtaManager` 위임) |
| `abortOta(): Boolean` | 진행 중인 OTA 중단 |

### 2.8 게임 모드

| 메서드 | 설명 |
|---|---|
| `supportsGameMode(): Boolean` | FF03/FF04 지원 여부 |
| `setNotifyGameResults(onResult: (GameResult) -> Unit): Boolean` | FF04 Notify 활성화만 함(명령 전송 없음) |
| `sendGameCmd(cmd: GameCmd, mode: GameMode? = null, level: Int = 0, option: Int = 0, wandId: Int = 0, onResult: ((GameResult) -> Unit)? = null): Boolean` | **모든 게임 명령의 단일 진입점**. `onResult`를 넘기면 전송 전에 `setNotifyGameResults`를 자동 호출(재호출해도 안전). |
| `clearNotifyGameResults()` | FF04 Notify 비활성화 (명령 전송 없음) |

`sendGameCmd`의 파라미터 의미는 `cmd`에 따라 달라진다:

| `cmd` | 사용 파라미터 |
|---|---|
| `GameCmd.START` | `mode` + `level`(난이도, Mode4는 라운드 수 1~3) + `option`(Mode3 랜덤팀=`GAME_OPTION_RANDOM_TEAM`, Mode4는 라운드당 측정시간ms) |
| `GameCmd.WINNER` | `mode`(Mode1/2 한정 — `TEAM_BATTLE`이면 `false` 반환) + `wandId` |
| `GameCmd.TEAM_ASSIGN` / `GameCmd.TEAM_ASSIGN_END` | `level` = 팀 id (0=RED/1=BLUE), `mode` 무시(항상 Mode4) |
| `GameCmd.STOP` / `GameCmd.CLEAR` | 추가 파라미터 없음 |

companion 상수: `Device.GAME_OPTION_RANDOM_TEAM = 0xFF` (Mode3 랜덤 팀 배정용 `option` 값)

### 2.9 그룹 세팅

| 메서드 | 설명 |
|---|---|
| `sendGroupSetting(groupId: Int): Boolean` | GroupSetup 비콘 방송(groupId: 1~20). 그룹 *컨트롤*은 `sendEffect`로 하며 별도 메서드 없음. |

---

## 3. 상태/정보 타입 (`com.lightstick.device`)

### `ConnectionState` (sealed class)
```kotlin
sealed class ConnectionState {
    data class Disconnected(reason: DisconnectReason = UNKNOWN, timestamp: Long)
    data class Connecting(timestamp: Long)
    data class Connected(timestamp: Long, mtu: Int? = null)
    data class Disconnecting(timestamp: Long)
    enum class DisconnectReason { USER_REQUESTED, DEVICE_POWERED_OFF, TIMEOUT, OUT_OF_RANGE, GATT_ERROR, UNKNOWN }
}
```

### `DeviceInfo` (data class)
```kotlin
data class DeviceInfo(
    val modelName: String? = null,       // GAP 2A00 (펌웨어 내부 모델명)
    val deviceName: String? = null,      // 스캔 advertising에서 관찰된 이름
    val modelNumber: String? = null,     // DIS 2A24
    val firmwareRevision: String? = null,// DIS 2A26
    val manufacturer: String? = null,    // DIS 2A29
    val batteryLevel: Int? = null,       // BAS 2A19, 0..100
    val macAddress: String,
    val rssi: Int? = null,
    val isConnected: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)
```

### `DeviceState` (data class)
```kotlin
data class DeviceState(
    val macAddress: String,
    val connectionState: ConnectionState,
    val deviceInfo: DeviceInfo? = null,  // DIS 읽기 전이면 null
    val lastSeenTimestamp: Long
)
```

### `DeviceInfoResult` (sealed class) — `LSBluetooth.getCachedDeviceInfo()` 반환용
```kotlin
sealed class DeviceInfoResult {
    data class Available(val info: DeviceInfo) : DeviceInfoResult()
    data class Error(val code: ErrorCode) : DeviceInfoResult()
    enum class ErrorCode { NOT_CONNECTED, INFO_NOT_READY }
}
```

---

## 4. `DeviceFilter` (`com.lightstick.config`)

`LSBluetooth.initialize(context, deviceFilter = ...)`에 전달해 스캔/연결/페어링목록/시스템복원 전체에 적용되는 전역 필터.

### 4.1 팩토리

| 메서드 | 설명 |
|---|---|
| `DeviceFilter.byName(pattern: String, mode: MatchMode = CONTAINS, ignoreCase: Boolean = true)` | 이름 패턴 매칭 |
| `DeviceFilter.byMacPrefix(prefix: String)` | MAC OUI(제조사) prefix 매칭 |
| `DeviceFilter.byMacPrefixes(vararg prefixes: String)` | 여러 prefix를 OR로 매칭 |
| `DeviceFilter.byMacAddress(macAddress: String)` | 정확히 하나의 MAC만 매칭 |
| `DeviceFilter.byMinRssi(minRssi: Int)` | 최소 RSSI 이상만 통과 |
| `DeviceFilter.byRssiRange(minRssi: Int, maxRssi: Int)` | RSSI 범위 |
| `DeviceFilter.custom(predicate: (Device) -> Boolean)` | 커스텀 조건 |
| `DeviceFilter.acceptAll()` / `DeviceFilter.rejectAll()` | 전체 허용/전체 거부 |

`MatchMode`: `CONTAINS`, `STARTS_WITH`, `ENDS_WITH`, `EQUALS`, `REGEX`

### 4.2 조합

| 메서드 | 설명 |
|---|---|
| `filter.and(other)` | AND 결합 |
| `filter.or(other)` | OR 결합 |
| `filter.not()` | 반전 |
| `DeviceFilter.Builder().addName(...).addName(...).build()` | 이름 패턴 여러 개를 조건부로 누적해서 OR-결합할 때(고정 리스트가 아닐 때) 사용하는 가변 빌더 |

```kotlin
val filter = DeviceFilter.byName("LS")
    .and(DeviceFilter.byMacPrefix("AA:BB:CC"))
    .and(DeviceFilter.byMinRssi(-70))
LSBluetooth.initialize(context, deviceFilter = filter)
```

---

## 5. 색상 / 이펙트 타입 (`com.lightstick.types`)

### `Color` / `Colors`
```kotlin
data class Color(val r: Int, val g: Int, val b: Int)   // 0..255 각 채널
fun Color.toRgbBytes(): ByteArray                        // [r,g,b] 3바이트
```
`Colors`: `BLACK`, `WHITE`, `RED`, `GREEN`, `BLUE`, `YELLOW`, `CYAN`, `MAGENTA`, `ORANGE`, `PURPLE`, `PINK` (프리셋 상수)

### `EffectType` (enum, code)
| 값 | code |
|---|---|
| OFF | 0 |
| ON | 1 |
| STROBE | 2 |
| BLINK | 3 |
| BREATH | 4 |

`EffectType.fromCode(code)` — 알 수 없는 코드는 `OFF`로 안전 폴백.

### `MsgType` (enum, code) — FF02 offset 0
| 값 | code | 설명 |
|---|---|---|
| EFFECT | 0 | 단일/그룹 이펙트 (기본값) |
| GAME_MODE | 1 | FF02에서는 사용 안 함 (FF03/FF04 전용 레이아웃) |
| GROUP_SETUP | 2 | `Device.sendGroupSetting`에서만 사용 |

### `Group` (object, 상수 전용)
```kotlin
object Group {
    val GRP1..GRP32: Long   // 1L shl (N-1)
    val ALL_SINGLE: Long = 0L
    val ALL_GROUPS: Long = 0xFFFFFFFFL
}
```
`LSEffectPayload.groupMask`에 OR로 조합해서 사용: `Group.GRP1 or Group.GRP3`.

### `GroupPalette` (object) — 그룹 1~20 고정 팔레트
```kotlin
object GroupPalette {
    const val MIN_GROUP_ID = 1
    const val MAX_GROUP_ID = 20
    val PALETTE: List<Color>
    fun colorFor(groupId: Int): Color   // 1..20, 범위 밖이면 IllegalArgumentException
}
```
골든 앵글(≈137.508°) 방식으로 생성되어 인접 그룹 번호끼리도 색이 뚜렷이 구분됨. `sendGroupSetting`이 내부적으로 이 팔레트를 사용.

### `LSEffectPayload` (data class) — FF02 20바이트 페이로드
```kotlin
data class LSEffectPayload(
    val groupMask: Long = Group.ALL_SINGLE,
    val effectType: EffectType = EffectType.ON,
    val color: Color = Colors.WHITE,
    val backgroundColor: Color = Colors.BLACK,
    val msgType: MsgType = MsgType.EFFECT,
    val period: Int = 10,
    val spf: Int = 100,
    val randomColor: Int = 0,
    val randomDelay: Int = 0,
    val fade: Int = 100,
    val broadcasting: Int = 0,
    val effectIndex: Int = 0
)
fun toByteArray(): ByteArray                       // 20바이트 인코딩
companion fun fromByteArray(bytes: ByteArray): LSEffectPayload  // 디코딩 (msgType=GAME_MODE면 예외)
```
정확한 바이트 오프셋/범위는 [`BLE_PROTOCOL.md` §2.2](./BLE_PROTOCOL.md#22-ff02--effect-payload-20바이트-le_led_patload_t-protocol-v20)참고.

**`LSEffectPayload.Effects`** — 자주 쓰는 이펙트 팩토리:
| 함수 | 설명 |
|---|---|
| `Effects.on(color, transit=0, ...)` | 상시 점등 |
| `Effects.off(transit=0, ...)` | 소등 |
| `Effects.strobe(period, color, backgroundColor=BLACK, ...)` | 스트로브 |
| `Effects.blink(period, color, backgroundColor=BLACK, ...)` | 블링크 |
| `Effects.breath(period, color, backgroundColor=BLACK, ...)` | 브레스 |

모두 `msgType`/`groupMask`/`spf`/`fade`/`broadcasting`/`effectIndex`를 선택 파라미터로 받아 `LSEffectPayload`를 반환한다.

---

## 6. 게임 모드 타입 (`com.lightstick.game`)

### `GameCmd` (enum, cmdIndex) — `Device.sendGameCmd`의 명령 인자
```kotlin
enum class GameCmd(val cmdIndex: Int) {
    START(1), STOP(3), CLEAR(4), WINNER(6), TEAM_ASSIGN(7), TEAM_ASSIGN_END(9)
}
```

### `GameMode` (enum, subIndex)
| 값 | subIndex | 설명 |
|---|---|---|
| SPEED_REACTION | 1 | LED ON 후 빠르게 흔들기, 5선승 |
| TEMPO | 2 | LED 템포에 맞춰 20초 내 5회 연속 |
| TEAM_BATTLE | 3 | RED/BLUE 5라운드 팀전 |
| TEAM_SIMULTANEOUS | 4 | 수동 팀배정(§2.8) 후 1~3라운드 동시 흔들기 |

`GameMode.fromSubIndex(subIndex): GameMode?`
`GameMode.resultTimeoutMs(level: GameLevel): Long` — `sendGameCmd(START, ...)` 후 결과 Notify를 기다릴 최대 시간(ms). `TEAM_SIMULTANEOUS`는 라운드수/측정시간이 앱 재량이라 최악값(3라운드×8초) 기준 보수적 상한(30_000L) 반환.

### `GameLevel` (enum, value)
```kotlin
enum class GameLevel(val value: Int) { EASY(1), NORMAL(2), HARD(3) }
```

### `GameResult` (data class) — FF04 Notify 파싱 결과
```kotlin
data class GameResult(
    val mode: GameMode,
    val cmdIndex: Int,       // CMD_RESULT(5) 또는 CMD_TEAM_CONFIRM(8)
    val redScore: Int,
    val blueScore: Int,
    val totalCount: Int,
    val wandId: Int
) {
    val isWandIdValid: Boolean   // cmdIndex==CMD_RESULT && wandId not in {0x0000,0xFFFF}
    companion object { const val CMD_RESULT = 5; const val CMD_TEAM_CONFIRM = 8 }
}
```
필드 의미는 `cmdIndex`에 따라 달라짐 — 상세는 [`BLE_PROTOCOL.md` §2.4](./BLE_PROTOCOL.md#24-ff04--game-result-notify-20바이트-ff03과-동일한-레이아웃-b-공유) 참고.

---

## 7. EFX 타임라인 파일 포맷 (`com.lightstick.efx`)

`.efx` 바이너리(헤더 + 프레임 목록)를 만들고 읽기 위한 타입들. 재생 자체는 `Device.playEffects`/`playTimeline`으로 한다(프레임 리스트만 여기서 만듦).

### `EfxHeader` (data class)
```kotlin
data class EfxHeader(
    val magic: String = "EFX1",
    val version: Int = 0x0104,
    val reserved: ByteArray = [0,0,0],
    val musicId: Int = 0,
    val entryCount: Int = 0
)
```

### `EfxEntry` / `EfxBody`
```kotlin
data class EfxEntry(val timestampMs: Long, val payload: LSEffectPayload) {
    fun toFrame(): Pair<Long, ByteArray>
}
data class EfxBody(val entries: List<EfxEntry>) {
    constructor(vararg entries: EfxEntry)
    val size: Int
    fun toFrames(): List<Pair<Long, ByteArray>>   // timestampMs 오름차순 정렬
}
```

### `Efx` (data class) — 헤더+바디 전체
```kotlin
data class Efx(val header: EfxHeader, val body: EfxBody) {
    fun toByteArray(): ByteArray
    fun write(file: File): Boolean
    companion object {
        fun read(bytes: ByteArray): Efx
        fun read(file: File): Efx
        fun toFrames(entries: List<EfxEntry>): List<Pair<Long, ByteArray>>
    }
}
```
사용 예:
```kotlin
val efx = Efx.read(musicFile)
device.playTimeline(efx.body.toFrames())
```

### `MusicId` (object) — 결정적 32비트 음악 ID (SHA-256 앞 4바이트, LE)
| 메서드 | 설명 |
|---|---|
| `MusicId.fromFile(file: File): Int` | 20MB 이하는 전체, 초과 시 앞 4MB만 해시 |
| `MusicId.fromStream(stream: InputStream, filenameHint: String? = null): Int` | |
| `MusicId.fromUri(context: Context, uri: Uri): Int` | SAF/MediaStore 등 |

---

## 8. OTA (`com.lightstick.ota`)

### `OtaManager` (object)
| 메서드 | 설명 |
|---|---|
| `startOta(device: Device, firmwareBytes: ByteArray, preferredMtu: Int = 247, startOpcodes: ByteArray? = null, onProgress: (OtaProgress) -> Unit = {}, onResult: (OtaResult) -> Unit = {})` | OTA 시작. 연결 안 돼있으면 즉시 `onResult(false, ...)` |
| `abortOta(device: Device)` | 중단 (세션 없어도 안전) |
| `state(device: Device): Flow<OtaStatus>` | 상태 Flow (hot) |

`OtaManager.OtaStatus`(enum): `IDLE, PREPARING, NEGOTIATING_MTU, ENABLING_NOTIFY, TRANSFERRING, VERIFYING, COMPLETED, ABORTED, ERROR`

`OtaManager` 안에 **중첩된** 콜백 DTO (실제 `startOta` 콜백이 사용하는 타입):
```kotlin
data class OtaProgress(val percent: Int)          // OtaManager.OtaProgress
data class OtaResult(val ok: Boolean, val message: String? = null)  // OtaManager.OtaResult
```

내부 프로토콜(Telink Legacy OTA — MTU 협상 → START → PDU 전송 → END → RESULT notify)은 [`BLE_PROTOCOL.md` §4.6](./BLE_PROTOCOL.md#46-ota-telink-legacy-프로토콜-시퀀스) 참고.

`Device.startOta`/`Device.abortOta`는 이 `OtaManager`를 내부적으로 호출하는 편의 래퍼.

---

## 9. 부록: 정리된 미사용/데드 코드

아래는 이전에 발견되어 제거/수정된 항목의 기록. 현재 코드베이스에는 존재하지 않는다.

| 항목 | 조치 |
|---|---|
| `com.lightstick.config.InitConfig` / `InitResult` | 삭제 — 어디에서도 참조되지 않던 미완성 타입 (KDoc이 가리키던 `LSBluetooth.initializeAsync`도 존재한 적 없음) |
| `com.lightstick.ota.OtaProgress` / `com.lightstick.ota.OtaResult` (최상위) | 삭제 — `OtaManager` 안의 중첩 `OtaProgress`/`OtaResult`와 이름이 같아 혼동만 유발하던 미사용 중복 타입 |
| `com.lightstick.efx.Efx` KDoc의 `com.lightstick.device.Controller.play` 언급 | 수정 — 존재한 적 없는 클래스 참조를 `Device.playEffects`/`Device.playTimeline`으로 정정 |
| `lightstick/src/androidTest/.../LightStickBleIntegrationTest.kt`, `SimpleBleTest.kt` | 삭제 — 현재 존재하지 않는 `Controller` 타입과 옛 `connect(onConnected: (controller) -> Unit)` 시그니처를 참조하고 있어 컴파일 자체가 불가능했던 계측 테스트 |

---

## 10. 버전

`lightstick` / `lightstick-internal-core` / `lightstick-sdk` 세 모듈 모두 `group="com.lightstick"`, `version="2.0.0"`으로 통일 배포.
