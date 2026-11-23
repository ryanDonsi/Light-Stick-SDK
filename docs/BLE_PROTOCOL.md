# 응원봉 ↔ Android BLE 통신 프로토콜 (v2.2)

> 기준 규격: `GLOWSYNC_BLE_PROTOCOL_V.2.2` (2026-11-20). SDK 2.200.0이 이 문서의 내용을 구현한다.

| 버전 | 날짜 | 주요 변경 |
|---|---|---|
| **v2.2** | 2026-11-20 | 공연 제어(Control) 추가 — 신규 msgType=3(CONTROL), 레이아웃 C, 명령 SHOW_ENTER/SHOW_EXIT/PAUSE/RESUME/SYSTEM_OFF, 신규 FF0A 특성. 알 수 없는 msgType은 무시. 펌웨어 버전 규칙(§6) |
| v2.1 | 2026-11-05 | broadcasting 필드 삭제(→FF09), effectIndex u16→u8(offset 17), duration(u16, offset 18-19) 재추가 |
| v2.0 | 2026-08-25 | msgType 단일 판별자, groupMask 기반 그룹 제어, 레이아웃 A/B 분리 |

본 문서는 응원봉(펌웨어)과 Android 앱 사이의 **순수 BLE GATT 레벨** 통신 규격만을 다룬다.
SDK의 Kotlin API 이름/시그니처는 다루지 않으며, 오직 **서비스/캐릭터리스틱 UUID, 패킷(바이트) 구조, 송수신 시퀀스**만을 정리한다.

---

## 1. 개요

- 전송 방식: Bluetooth LE GATT (Central = Android 앱, Peripheral = 응원봉)
- 연결: MAC 주소 기반 1:1 GATT 연결 (`connectGatt`, TRANSPORT_LE)
- 쓰기(Write) 특성은 대부분 **Write Without Response**를 사용 (ATT 옵코드 0x52) — 응원봉이 ACK를 반환하지 않음을 전제로 설계됨
- 알림(Notify)이 필요한 캐릭터리스틱은 표준 CCCD(0x2902)에 `01 00`을 write해야 활성화됨
- 멀티바이트 정수 필드는 특별한 언급이 없는 한 **Little-Endian**

### 서비스 목록 요약

| 서비스 | UUID | 종류 | 용도 |
|---|---|---|---|
| LED Control Service (LCS) | `0001fe01-0000-1000-8000-00805f9800c4` | Custom | 색상/이펙트/그룹/게임 제어 (FF01~FF04), 설정/조회 (FF06, FF09), 공연 제어 (FF0A) |
| GAP | `00001800-0000-1000-8000-00805f9b34fb` | Standard | Device Name (0x2A00) |
| DIS (Device Information) | `0000180a-0000-1000-8000-00805f9b34fb` | Standard | 모델명/펌웨어버전/제조사 |
| BAS (Battery Service) | `0000180f-0000-1000-8000-00805f9b34fb` | Standard | 배터리 잔량 |
| MAC Service (Custom) | `0001fe03-0000-1000-8000-00805f9800c4` | Custom | 내부 MAC 주소 문자열 |
| OTA Service (Telink) | `1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0` | Custom (Telink 표준) | 펌웨어 업데이트 |
| Concert Library (Optional) | `0001fe02-0000-1000-8000-00805f9800c4` | Custom | 배치 EFX 동기화 (선택 구현) |

---

## 2. LED Control Service (LCS) — `0001fe01`

응원봉 제어의 핵심 서비스. FF01~FF04, FF06, FF09, FF0A 캐릭터리스틱이 모두 이 서비스 하나에 속한다.

| 캐릭터리스틱 | UUID | Property | 크기 | 용도 |
|---|---|---|---|---|
| FF01 (Color) | `0001ff01-...-00805f9800c4` | Write No Response | 4B | 단순 RGB 컬러 |
| FF02 (Payload) | `0001ff02-...-00805f9800c4` | Write No Response | 20B | 이펙트 / 그룹 컨트롤 / 그룹 셋업 / 타임라인 프레임 |
| FF03 (Game Cmd) | `0001ff03-...-00805f9800c4` | Write | 20B | 게임 모드 명령 |
| FF04 (Game Result) | `0001ff04-...-00805f9800c4` | Notify | ≥11B (20B 프레임) | 게임 결과 통지 |
| FF06 (Device Mode) | `0001ff06-...-00805f9800c4` | Read | 1B | 현재 모드 (0=중계기/1=BLE Only), spec v2.8 §2.1 |
| FF09 (Broadcasting) | `0001ff09-...-00805f9800c4` | Read / Write | 1B | BLE 모드 전용 — Effect 주변 전파 여부 (0=싱글/1=전파, 기본 1), §2.6 |
| **FF0A (Control)** | `0001ff0a-...-00805f9800c4` | Write (With Response) | 20B | **공연 제어 — 공연 모드 진입/해제, 일시정지, 재개, 전원 끄기 (§2.7, v2.2 신규)** |

### 2.1 FF01 — Color Packet (4바이트)

가장 단순한 컨트롤 채널. 즉시 해당 RGB로 전환.

| Offset | 필드 | 타입 | 설명 |
|---|---|---|---|
| 0 | R | u8 | 0~255 |
| 1 | G | u8 | 0~255 |
| 2 | B | u8 | 0~255 |
| 3 | transition | u8 | 전환 파라미터 (펌웨어 종속) |

### 2.2 FF02 — Effect Payload (20바이트, `LE_LED_PATLOAD_T`, protocol v2.1 이후 동일)

`msgType`(offset 0) 하나가 프레임 전체의 **유일한 메시지 종류 판별자**다. `EFFECT`와 `GROUP_SETUP`은 아래와 동일한 레이아웃(레이아웃 A)을 공유하고, `GAME_MODE`는 FF03/FF04 전용의 다른 레이아웃(레이아웃 B, §3)을 사용한다 — 즉 msgType=1(GAME_MODE)인 프레임은 FF02가 아니라 FF03/FF04에서만 등장한다. v2.2에서 추가된 `CONTROL`(msgType=3)은 레이아웃 C(§2.7)를 사용하며 FF0A에서만 등장한다.

| Offset | 필드 | 타입 | 설명 |
|---|---|---|---|
| 0 | msgType | u8 | `0`=EFFECT, `1`=GAME_MODE(FF02에서는 미사용), `2`=GROUP_SETUP, `3`=CONTROL(FF02에서는 미사용, FF0A 전용 §2.7) |
| 1-4 | groupMask | u32 LE | `0`=그룹 무시(단일/전체), `0xFFFFFFFF`=모든 그룹, bit(N-1)=1 → 그룹 N 타겟 (N=1~32, OR 조합 가능) |
| 5-7 | fgColor (R,G,B) | 3×u8 | 전경색 |
| 8-10 | bgColor (R,G,B) | 3×u8 | 배경색 |
| 11 | effectType | u8 | `0`=OFF, `1`=ON, `2`=STROBE, `3`=BLINK, `4`=BREATH |
| 12 | period | u8 | 0~255, 이펙트 주기 |
| 13 | spf | u8 | 0~255, frame당 sample 수 / 타이밍 파라미터 |
| 14 | randomColor | u8 | `0`=비활성, `1`=활성 |
| 15 | randomDelay | u8 | 0=없음, 1~255 = 10ms 단위 최대 랜덤 지연 |
| 16 | fade | u8 | 0~255, 페이드 파라미터 |
| 17 | effectIndex | u8 | 순수 dedup/시퀀스 번호(0~255, 순환). 메시지 종류 판별에는 관여하지 않음 (v2.0: u16, offset 18-19) |
| 18-19 | duration | u16 LE | 이펙트 지속 시간. 1=10ms (최대 65,535 ≈ 655초). `0`=다음 명령이 올 때까지 유지 (v2.1 재추가) |

**duration (v2.1)**: 이펙트 시작 후 `duration × 10ms`가 지나면 응원봉이 LED를 끈다. `0`이면 타이머 없이 다음 명령까지 유지(v2.0 동작). 새 Effect/GroupSetup/게임 명령이 도착하면 이전 타이머는 취소되고 새 명령의 duration으로 다시 시작된다. 타임라인처럼 프레임을 연속 전송할 때는 프레임 간격보다 짧은 duration이 프레임 사이에 LED를 끄므로 `0`을 권장한다 (SDK 타임라인 재생은 프레임과 재전송 프레임의 duration을 항상 0으로 고정한다 — `Device.sendEffect`는 값을 그대로 보낸다).

**duration 사용 규칙 (v2.2 명시)**: 0이 아닌 duration은 **10 이상(100ms 이상) 권장** — 중계기 모드에서 같은 Effect가 burst로 여러 번(약 60ms 폭) 도착해 사본마다 타이머가 재시작되므로 1~9는 만료 시점이 부정확하다(펌웨어는 값을 보정하지 않는다). duration은 **그룹 대상(groupMask≠0) Effect에도 동일하게 적용**된다. 공연 제어 PAUSE(§2.7)는 이 타이머를 멈추고 RESUME은 처음부터 다시 센다.

**동일 프레임 재전송 주의 (effectIndex)**: 중계기(FF02 수신 시), BLE 모드 Master 응원봉, 슬레이브 응원봉은 직전에 받은 20바이트와 완전히 같은 프레임을 중복으로 보고 무시한다. 같은 이펙트를 다시 보낼 때는 **effectIndex를 바꿔서** 보낸다 — duration이 만료되어 꺼진 뒤 다시 켜는 경우도, 그룹 대상 Effect(groupMask≠0)도 같다(effectIndex가 바뀌면 같은 내용이라도 새 명령으로 처리). 중계기 모드의 사본 4개는 같은 effectIndex이므로 한 번만 처리된다. **`Device.sendEffect`는 effectIndex를 자동 증가시키지 않는다 — 앱이 관리한다**(타임라인 재생만 SDK가 자체 관리).

**broadcasting 삭제 (v2.1)**: 페이로드에서 broadcasting 필드가 삭제되어 항상 브로드캐스트로 동작한다. BLE 모드에서 전파를 끄려면 FF09(§2.6)에 `0`을 쓴다.

**groupMask와 GroupControl**: 그룹 대상 제어는 별도의 msgType이 없다 — `msgType=EFFECT` + `groupMask`만으로 "특정 그룹", "여러 그룹 조합(OR)", "전체 기기"를 모두 표현한다.

**GroupSetup (msgType=2)**: 필드 레이아웃은 EFFECT와 동일하되 의미가 다르다 — 진행자가 "그룹 N 가입" 화면을 띄우는 동안 반복 전송하는 비콘. 그룹 미배정 상태의 응원봉은 `fgColor`로 지정된 색으로 BLINK하고, 사용자가 버튼을 누르면 해당 그룹으로 잠긴다. 명시적인 "종료" 메시지는 없음 — 다음 그룹으로 넘어갈 때 새 groupId로 다시 전송한다.

### 2.3 FF03 — Game Command (20바이트, 레이아웃 B)

| Offset | 필드 | 타입 | 설명 |
|---|---|---|---|
| 0 | msgType | u8 | 항상 `1` (GAME_MODE) |
| 1 | subIndex | u8 | 게임 모드 `1`~`4` |
| 2 | cmdIndex | u8 | 아래 명령 코드 표 참조 |
| 3 | level | u8 | Mode1/2/3: 난이도. Mode4 TEAM_ASSIGN/END: 팀 id(0=RED/1=BLUE). Mode4 READY: 라운드 수(1~3) |
| 4-5 | option | u16 LE | Mode3 랜덤팀 지정 시 `0xFF`. Mode4 READY: 라운드당 측정시간(ms) |
| 6-8 | (미사용) | — | `0` |
| 9-10 | wandId | u16 LE | `cmdIndex=WINNER`일 때만 사용 — 우승 완드 id |
| 11-16 | (미사용) | — | `0` |
| 17 | effectIndex | u8 | 하향 dedup용, 펌웨어가 메시지 판별에 사용하지 않음(0 가능). v2.1: u16(18-19) → u8(17) |
| 18-19 | (예약) | — | `0` (레이아웃 A의 duration 자리, 게임 명령에서는 미사용) |

**cmdIndex 명령 코드**

| 값 | 이름 | 설명 |
|---|---|---|
| 1 | READY | 게임 시작 |
| 3 | STOP | 게임 중단 |
| 4 | CLEAR | 대기 상태로 리셋 |
| 5 | RESULT | (FF04 전용, 결과 통지) |
| 6 | WINNER | 우승 완드 발표 |
| 7 | TEAM_ASSIGN | (Mode4 전용) 팀 배정 시작 |
| 8 | TEAM_CONFIRM | (FF04 전용, 팀 배정 확정 통지) |
| 9 | TEAM_ASSIGN_END | (Mode4 전용) 팀 배정 종료 — 802.15.4로 완드에 전달되지 않고 릴레이가 로컬 처리 |

### 2.4 FF04 — Game Result Notify (20바이트, FF03과 동일한 레이아웃 B 공유)

| Offset | 필드 | 타입 | 설명 |
|---|---|---|---|
| 0 | msgType | u8 | 항상 `1` (GAME_MODE) |
| 1 | subIndex | u8 | 게임 모드 `1`~`4` |
| 2 | cmdIndex | u8 | `5`=RESULT 또는 `8`=TEAM_CONFIRM(집계) — 아래 필드 의미가 이 값에 따라 달라짐 |
| 3-4 | redScore | u16 LE | RESULT: Mode1/2 개인 점수 / Mode3-4 RED팀 합계. TEAM_CONFIRM: 미사용(0) |
| 5-6 | blueScore | u16 LE | RESULT: Mode1/2 항상 0 / Mode3-4 BLUE팀 합계. TEAM_CONFIRM: 미사용(0) |
| 7-8 | totalCount | u16 LE | RESULT: 지금까지 보고한 누적 완드 수(Mode1/2). TEAM_CONFIRM: TEAM_ASSIGN_END로 방금 종료된 팀의 확정 인원수 |
| 9-10 | wandId | u16 LE | RESULT: Mode1/2 보고 완드 id (Mode3/4는 0). TEAM_CONFIRM: 팀 id로 재해석 (0=RED/1=BLUE) |
| 11-16 | (예약) | — | — |
| 17 | effectIndex | u8 | 이 경로에서는 미사용(신뢰성 있는 1회성 Notify), 항상 0. v2.1: u16(18-19) → u8(17) |
| 18-19 | (예약) | — | — |

> **TEAM_CONFIRM(8)의 특성**: 개별 완드의 팀 락인은 802.15.4 상에서 각자 자신의 TEAM_CONFIRM으로 오르지만, 릴레이가 이를 내부적으로 집계만 하고 있다가 앱이 `TEAM_ASSIGN_END(9)`를 보낸 뒤 **팀당 1회, 집계된 인원수**로 FF04에 통지한다.

### 2.5 FF06 — Device Mode (1바이트, Read-only, spec v2.8 §2.1)

| Offset | 필드 | 타입 | 설명 |
|---|---|---|---|
| 0 | mode | u8 | `0`=중계기 모드 / `1`=BLE Only 모드 |

앱은 연결 직후 1회 Read해서 현재 응원봉이 어느 모드로 동작 중인지 확인한다. BLE Only 모드는 홈파티·소규모 모임을 대상으로 하며 그룹 제어(1~20그룹)와 게임모드 4(수동 팀배정+라운드제)를 지원하지 않으므로, 앱은 이 값에 따라 해당 메뉴를 비활성화해야 한다(spec §1.3).

### 2.6 FF09 — Broadcasting 설정 (1바이트, v2.1 신규)

| 항목 | 내용 |
|---|---|
| UUID | `0001ff09-0000-1000-8000-00805f9800c4` (서비스 `0001fe01`) |
| Property | Read / Write (Write With Response) |
| 값 | `0`=싱글(연결된 응원봉만 반응), `1`=주변 전파 (기본값). 0/1 이외의 값은 1로 처리 |
| 존재하는 기기 | BLE 모드 응원봉(Master)만. 중계기에는 없다(항상 802.15.4 브로드캐스트) |

- 앱은 FF06이 1(BLE 모드)일 때만 FF09를 사용한다.
- 응원봉은 연결될 때마다 기본값 1로 시작하고 연결이 끊기면 1로 돌아간다(RAM만). 0이 필요하면 **연결할 때마다 다시 써야 한다**.
- FF02(Effect/GroupSetup) 전파에만 적용된다. 게임 명령(FF03)은 항상 전파된다.
- 새 characteristic이 추가되었으므로 이전 펌웨어와 본딩된 기기는 GATT 캐시를 갱신(서비스 재탐색)해야 FF09가 보인다 — SDK는 본딩된 기기에 연결할 때 `BluetoothGatt.refresh()`를 호출한 뒤 서비스를 탐색한다.

### 2.7 FF0A — Control (공연 제어, v2.2 신규)

공연 모드 진입/해제, 일시정지, 재개, 전원 끄기는 연결되지 않은 응원봉 수백 대에 방송으로 도달해야 한다. 중계기 모드에서 앱은 중계기와만 GATT로 연결하므로 응원봉 attribute에는 쓸 수 없고, Effect/게임과 같이 **20바이트 프레임(msgType=3)** 으로 전달한다. FF0A는 그 프레임을 넣는 입구 특성이다.

| 항목 | 내용 |
|---|---|
| UUID | `0001ff0a-0000-1000-8000-00805f9800c4` (서비스 `0001fe01`) |
| Property | Write (Write With Response) |
| 크기 | 20바이트 (레이아웃 C) |
| 존재하는 기기 | 중계기, BLE 모드 응원봉(Master) |

- **중계기 모드**: 중계기가 받은 프레임을 802.15.4로 방송한다(다른 방송 프레임처럼 사본 반복 전송). 중계기 자신은 control 대상이 아니다(전원 끄기 포함).
- **BLE 모드**: Master 응원봉이 자신도 처리하고 주변 응원봉에 광고로 전파한다. FF09(Broadcasting) 설정과 **무관하게 항상 전파**한다(게임 명령과 동일).
- 새 characteristic이므로 이전 펌웨어와 본딩된 기기는 GATT 캐시를 갱신해야 FF0A가 보인다 — SDK는 본딩된 기기 연결 시 `BluetoothGatt.refresh()` 후 서비스를 탐색한다.

#### 2.7.1 Control 프레임 — 레이아웃 C (20바이트)

| Offset | 필드 | 타입 | 설명 |
|---|---|---|---|
| 0 | msgType | u8 | 항상 `3` (CONTROL) |
| 1-4 | groupMask | u32 LE | 레이아웃 A와 같은 위치·의미 — `0`=그룹과 무관하게 **모든 응원봉**, `0xFFFFFFFF`=그룹이 배정된 모든 응원봉, bit(N-1)=1 → 그룹 N (OR 조합) |
| 5 | cmdIndex | u8 | 제어 명령 코드(§2.7.3) |
| 6-16 | (예약) | --- | 0 |
| 17 | effectIndex | u8 | 순번(dedup) — 새 명령(재전송 포함)마다 값을 바꾼다 |
| 18-19 | (예약) | --- | 0 |

- 그룹이 배정되지 않은 응원봉은 groupMask≠0인 control을 **무시**한다(Effect와 같은 규칙). SHOW_ENTER/SHOW_EXIT는 groupMask=0으로 보낸다.
- 알 수 없는 cmdIndex는 무시한다(상태 변화 없음).
- **v2.2 펌웨어부터 알 수 없는 msgType은 Effect로 해석하지 않고 무시한다.** SDK도 같은 규칙을 따른다: `MsgType.fromCode(unknown)`은 `null`을 반환하고 `LSEffectPayload.fromByteArray`는 알 수 없는 msgType을 거부한다.

#### 2.7.2 프레임 공통 구조 — 레이아웃 A / B / C

| Offset | 레이아웃 A (EFFECT/GROUP_SETUP) | 레이아웃 B (GAME_MODE) | 레이아웃 C (CONTROL) |
|---|---|---|---|
| 0 | msgType | msgType | msgType |
| 1-4 | groupMask (u32) | subIndex / cmdIndex / level / option(4-5) | groupMask (u32) |
| 5 | fgColor R … | option(상위) | cmdIndex |
| 6-16 | fg/bg 색, effectType, period, spf, random, fade | 미사용 / wandId(9-10) | 예약(0) |
| 17 | effectIndex (u8) | effectIndex (u8) | effectIndex (u8) |
| 18-19 | duration (u16) | 예약 | 예약 |

게임 프레임(B)은 groupMask가 없고 cmdIndex가 offset 2에 있다. v2.1 게임 프레임과 호환을 위해 v2.2에서는 C만 A/B에 맞추고 B는 그대로 둔다.

#### 2.7.3 제어 명령 (cmdIndex)

| 값 | 이름 | 의미 |
|---|---|---|
| 1 | SHOW_ENTER | 공연 모드 진입 |
| 2 | SHOW_EXIT | 공연 모드 해제 |
| 3 | PAUSE | 현재 효과를 **얼린다**(일시정지) |
| 4 | RESUME | 얼린 효과를 **처음부터 재시작**한다 |
| 5 | SYSTEM_OFF | 응원봉 **전원 끄기**(시스템 종료) |

**멱등 원칙** — 방송에는 ACK가 없어 일부 응원봉이 명령을 놓치면 앱이 다시 보내야 하므로, 모든 명령은 "현재 상태에서 의미가 있을 때만" 동작하고 아니면 무시한다. 같은 명령을 effectIndex만 바꿔 여러 번 보내도 안전하다.

| 명령 | 동작하는 조건 | 이미 그 상태이면 |
|---|---|---|
| SHOW_ENTER | 공연 모드가 아닐 때 | 무시 |
| SHOW_EXIT | 공연 모드이거나 일시정지 중일 때 | 무시 |
| PAUSE | 효과가 재생 중이고 일시정지가 아닐 때 | 무시 |
| RESUME | **일시정지 중일 때만** | 무시 (재생 중이면 다시 시작하지 않음) |
| SYSTEM_OFF | **항상** (공연 모드·일시정지·게임·그룹 셋업 중에도) | 이미 꺼진 응원봉은 수신하지 못하므로 영향 없음 |

**SHOW_ENTER / SHOW_EXIT**: 공연 모드 상태는 RAM에만 유지하며 재부팅하면 해제된다. 공연 모드 중에는 전원 끄기(길게 누르기)를 제외한 모든 버튼을 무시한다(게임 진행 중 게임 규칙의 버튼, 그룹 가입 대기 중 그룹 확정 버튼은 허용). 고객 요구에 따라 버튼 전원 끄기까지 막는 빌드 옵션이 있어도 SYSTEM_OFF로는 전원을 끌 수 있다. 공연 모드는 **명시적인 SHOW_ENTER로만** 진입한다. SHOW_ENTER는 LED를 바꾸지 않는다. SHOW_EXIT는 일시정지 중이었다면 해제하고 **소등**한다(일시정지가 아니면 LED 그대로).

**PAUSE**: 효과의 애니메이션 진행을 멈추고 지금 표시 중인 색에서 LED를 고정한다. duration 타이머도 멈춘다. 일시정지 중에도 수신은 계속한다. 재생 중인 효과가 없으면 아무 일도 없다.

**RESUME**: 일시정지 직전에 재생하던 효과(마지막으로 적용된 Effect 프레임, 그룹 대상 포함 — GroupSetup과 게임 명령은 기억하지 않음)를 **처음부터 다시 재생**한다. randomColor/randomDelay는 새로 뽑고 duration은 전체를 다시 센다. 일시정지 중이 아니면 무시한다. 응원봉마다 **자신이 명령을 받은 시점**에 재시작하므로 사본 도착 시점 차이(중계기: 약 60ms 폭)나 광고 전파 지연(BLE 모드)만큼 위상이 어긋날 수 있다.

**SYSTEM_OFF**: LED를 끄고 전원을 끈다(버튼 길게 누르기와 같은 종료). 어떤 상태에서도 동작한다. **복구 불가** — 꺼진 응원봉은 원격으로 켤 수 없다. 앱은 사용자 확인 절차(UI)를 거친 뒤에만 보내야 하며, **SDK는 확인 절차를 제공하지 않는다**. 앱이 연결된 BLE 모드 Master는 전원이 꺼지며 연결이 끊어진다.

**일시정지 중 도착한 다른 명령**

| 도착한 것 | 동작 |
|---|---|
| 새 EFFECT (그룹 대상 포함) | 일시정지를 **해제**하고 새 효과를 즉시 재생 (마지막 명령 우선) |
| GROUP_SETUP | 일시정지 해제 후 GroupSetup 동작 |
| 게임 명령 (FF03) | 일시정지 해제, 게임이 LED 제어 |
| SHOW_EXIT | 일시정지 해제 + 공연 모드 해제 + 소등 |
| SYSTEM_OFF | 전원 끄기 |
| 같은 PAUSE | 무시 |

공연 모드와 PAUSE는 독립 상태이다. 일시정지 중에는 duration이 만료되지 않는다.

#### 2.7.4 SDK 동작과 한계

- **API**: `Device.sendControl(cmd, groupMask)` 및 `showEnter/showExit/pauseShow/resumeShow/systemOff`. 호출마다 SDK가 effectIndex(1..255 순환)를 자동 증가시킨다. 존재 확인은 `supportsControl()`(FF0A 유무).
- **타임라인 pause/resume 연동**: `Device.pauseTimeline()`은 SDK 전송 정지와 함께 FF0A가 있는 기기에 Control `PAUSE`(groupMask=0)를 보내고, `Device.resumeTimeline()`은 재전송할 프레임이 없어도 항상 `RESUME`(groupMask=0)을 먼저 보낸 뒤 필요하면 현재 프레임을 재전송한다. FF0A가 없는 기기는 v2.1과 동일하게 SDK 전송 정지/재개만 한다. 대상은 연결된 기기 하나가 아니라 중계기/Master를 통해 도달하는 **모든 응원봉**이다.
- **호출 순서**: 응원봉이 PAUSE 중일 때 새 Effect/타임라인 프레임이 도착하면 일시정지가 해제된다. 일시정지 중에는 프레임을 보내지 말고, 일시정지 중 `sendEffect`를 보낸 경우 `resumeTimeline()`을 마지막에 호출한다(이 경우 RESUME은 응원봉이 무시하고 프레임이 재전송된다). RESUME은 효과를 **처음부터** 재시작하므로 타임라인 위치와 응원봉 애니메이션 위상은 다를 수 있다.
- **버전 한계**: `sendControl`은 연결 여부와 FF0A 존재만 확인하며 펌웨어 버전은 확인하지 않는다. 앱이 아는 것은 연결된 기기 하나의 버전이다. v2.2 중계기 뒤의 응원봉이 v2.1이면 SDK는 이를 알 수 없고, 해당 응원봉은 control 프레임을 Effect로 읽어 **소등**한다(SYSTEM_OFF도 전원이 꺼지지 않고 소등만 됨). 모든 중계기/응원봉을 2.200 이상으로 갱신한 뒤에만 control을 사용한다(§6.4, `Device.supportsProtocol(2, 2)`는 연결된 기기만 판단).
- ACK/상태 통지는 v2.2 범위에 없다.

---

## 3. GAP / DIS / BAS / Custom MAC — 표준 정보 조회

| 서비스 | 캐릭터리스틱 | UUID | Property | 내용 |
|---|---|---|---|---|
| GAP (`1800`) | Device Name | `2a00` | Read | 사용자에게 보여줄 기기 이름 (UTF-8, NUL 종단 가능) |
| DIS (`180a`) | Model Number | `2a24` | Read | 모델명 문자열 |
| DIS (`180a`) | Firmware Revision | `2a26` | Read | 펌웨어 버전 문자열 |
| DIS (`180a`) | Manufacturer Name | `2a29` | Read | 제조사 문자열 |
| BAS (`180f`) | Battery Level | `2a19` | Read | 1바이트, 0~100(%) |
| MAC Service (`0001fe03`) | MAC Char | `0001ff05` | Read | 내부 MAC 주소 — UTF-8 문자열(`AA:BB:CC:DD:EE:FF`) 또는 6바이트 raw 중 하나 |

문자열 캐릭터리스틱은 UTF-8로 인코딩되며, 첫 NUL(`0x00`) 바이트 이전까지만 유효 문자열로 취급한다.

---

## 4. 시퀀스

### 4.1 연결 및 초기화 시퀀스

```
Android                                   응원봉
   |── connectGatt(address, TRANSPORT_LE) ──▶
   |◀── onConnectionStateChange(CONNECTED) ──|
   |── discoverServices() ──────────────────▶
   |◀── onServicesDiscovered(GATT_SUCCESS) ──|
   |                                         |
   |  (이 시점부터 300ms 대기 후 DIS 순차 Read 시작 — 기기 안정화 목적)
   |                                         |
   |── Read 0x2A00 (Device Name, GAP) ───────▶
   |── Read 0x2A24 (Model Number) ───────────▶
   |── Read 0x2A26 (Firmware Revision) ──────▶
   |── Read 0x2A29 (Manufacturer) ───────────▶
   |── Read 0x2A19 (Battery Level, BAS) ──────▶
   |── Read 0001ff05 (Custom MAC) ────────────▶
   |◀── 각 Read 응답 ─────────────────────────|
```

- 각 DIS 필드 Read는 실패 시 최대 3회, 400ms 간격으로 재시도한다.
- Read/Write/Notify-CCCD-write는 모두 기기별 **직렬 큐**로 처리되어 동시에 여러 GATT 오퍼레이션이 겹치지 않는다 (Android BluetoothGatt는 오퍼레이션 동시 진행을 지원하지 않음).
- FF02(Write No Response) 연속 전송은 최소 호출 간격이 있으며, 같은 "키"의 대기 중인 쓰기는 최신 값으로 치환(coalesce)될 수 있다 — 단, **타임라인 프레임 전송은 이 치환 대상에서 제외**되어 프레임 순서가 보장된다.

### 4.2 단발 이펙트 / 그룹 컨트롤 전송

```
Android ── Write FF02 (msgType=EFFECT, groupMask=X, ...) [No Response] ──▶ 응원봉
```
- 그룹 컨트롤은 별도 시퀀스가 없다 — `groupMask` 값만 다른 동일한 FF02 write.
- ACK가 없으므로 성공 여부는 로컬 큐 등록 성공(=BLE 스택에 제출됨) 이상은 보장하지 않는다.

- 같은 Effect를 다시 보낼 때는 effectIndex를 바꿔야 한다(§2.2). `Device.sendEffect`는 자동 증가하지 않는다.

### 4.3 그룹 셋업(가입) 시퀀스

```
진행자 앱 ── Write FF02 (msgType=GROUP_SETUP, groupMask=1<<(N-1), fgColor=그룹N 색상, effectType=BLINK) [No Response] ──▶ 미배정 응원봉들
             (진행자가 "그룹 N 가입" 화면을 유지하는 동안 반복 전송)
             ...
             ── Write FF02 (groupMask=1<<(N+1-1), ...) ──▶  // 다음 그룹으로 전환
```
- 미배정 응원봉은 수신한 fgColor로 BLINK, 사용자가 버튼을 눌러 그 그룹에 락인.
- "그룹 셋업 종료" 전용 메시지는 없음 — 다음 groupId로 재전송하거나 진행자가 화면을 닫으면 종료.

### 4.4 연속 프레임(타임라인) 재생 시퀀스

```
Android ── Write FF02 (frame 0, effectIndex=K) [No Response] ──▶
        ── Write FF02 (frame 1, effectIndex=K) [No Response] ──▶
        ── ...                                                 ──▶
        ── Write FF02 (frame N, effectIndex=K) [No Response] ──▶
```
- 모든 프레임은 msgType=EFFECT로 고정 전송된다.
- 프레임의 `duration`은 0(다음 프레임/명령까지 유지)으로 고정한다.
- 같은 재생 세션 내 모든 프레임은 동일한 `effectIndex` 값(u8, 1~255 순환)을 사용 — 새 세션(재로드/재개) 시작 시 값이 증가하여 응원봉이 이전 세션 프레임과 새 세션 프레임을 구분(dedup)할 수 있게 한다.
- 프레임은 coalesce(치환) 대상에서 제외되어 순서와 개수가 보존된다.

### 4.5 게임 모드 시퀀스

**기본 흐름 (Mode 1/2/3 공통)**

```
Android ── Write CCCD 0001ff04 = 0x0100 (Notify 활성화) ──▶
       ◀── onDescriptorWrite(SUCCESS) ──|
Android ── Write FF03 (cmdIndex=READY, subIndex, level, option) ──▶ 응원봉/릴레이
                                                                    ... 게임 진행 ...
       ◀── Notify FF03 (cmdIndex=RESULT, redScore, blueScore, totalCount, wandId) ── (완드마다 개별 도착 가능, 여러 회)
Android ── Write FF03 (cmdIndex=WINNER, wandId) ──▶   // Mode1/2 한정
Android ── Write FF03 (cmdIndex=STOP 또는 CLEAR) ──▶
```

**Mode 4 (TEAM_SIMULTANEOUS) 팀 배정 흐름**

```
Android ── Write FF03 (subIndex=4, cmdIndex=TEAM_ASSIGN,     level=0(RED)) ──▶  // RED 배정 시작
                                                                 (완드들이 버튼으로 RED 락인, 802.15.4 상에서
                                                                  개별 TEAM_CONFIRM이 릴레이에만 집계됨)
Android ── Write FF03 (subIndex=4, cmdIndex=TEAM_ASSIGN_END, level=0(RED)) ──▶  // RED 배정 종료(릴레이 로컬 처리)
       ◀── Notify FF04 (cmdIndex=TEAM_CONFIRM, totalCount=RED인원수, wandId=0(RED)) ──|
Android ── Write FF03 (subIndex=4, cmdIndex=TEAM_ASSIGN,     level=1(BLUE)) ──▶ // BLUE 배정 시작
Android ── Write FF03 (subIndex=4, cmdIndex=TEAM_ASSIGN_END, level=1(BLUE)) ──▶ // BLUE 배정 종료
       ◀── Notify FF04 (cmdIndex=TEAM_CONFIRM, totalCount=BLUE인원수, wandId=1(BLUE)) ──|
Android ── Write FF03 (subIndex=4, cmdIndex=READY, level=라운드수, option=측정시간ms) ──▶  // 게임 시작
       ◀── Notify FF04 (cmdIndex=RESULT, redScore, blueScore, ...) ── (라운드마다)
```

### 4.6 OTA (Telink Legacy 프로토콜) 시퀀스

서비스: `1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0` / 데이터 캐릭터리스틱: `...f1` (Write + Notify)

**옵코드 (2바이트, Little-Endian)**

| 옵코드 | 값 | 방향 | 설명 |
|---|---|---|---|
| CMD_OTA_VERSION | 0xFF00 | → | (선택) 현재 펌웨어 버전 조회 |
| CMD_OTA_START | 0xFF01 | → | OTA 시작 (Legacy) |
| CMD_OTA_END | 0xFF02 | → | OTA 종료 |
| CMD_OTA_START_EXT | 0xFF03 | → | OTA 시작 (Extended, 본 SDK 미사용) |
| CMD_OTA_FW_VERSION_REQ | 0xFF04 | → | (Extended) 버전 비교 요청 |
| CMD_OTA_FW_VERSION_RSP | 0xFF05 | ← | (Extended) 버전 비교 응답 |
| CMD_OTA_RESULT | 0xFF06 | ← | OTA 완료/실패 결과 통지 |
| CMD_OTA_SET_FW_INDEX | 0xFF80 | → | (선택) 펌웨어 인덱스 지정 |

**PDU(데이터 패킷) 포맷**

```
[idx_lo][idx_hi][data 0 .. realPduLen-1][crc16_lo][crc16_hi]
```
- `idx`: 0부터 시작하는 패킷 순번 (u16 LE)
- `realPduLen = floor((MTU − 3(ATT) − 4(idx+crc)) / 16) × 16` — 반드시 16의 배수, 최소 16바이트
- 마지막 패킷은 남은 데이터를 16바이트 단위로 올림한 크기만큼 `0xFF`로 패딩
- CRC16: **CRC-16/ARC** (poly=0xA001, init=0xFFFF), 커버 범위 = idx(2B) + data

**END 커맨드 포맷 (총 20바이트 = 옵코드 2B + 페이로드 18B)**

```
[CMD_OTA_END(2B)] [lastIdx_lo][lastIdx_hi][~lastIdx_lo][~lastIdx_hi][0x00 × 14]
```
- `lastIdx`: 전송한 마지막 PDU의 0-based 인덱스
- `~lastIdx`: lastIdx의 비트 반전(보수) — 무결성 검증용

**시퀀스**

```
Android                                              응원봉
   |── requestMtu(preferredMtu) ────────────────────▶
   |◀── onMtuChanged(negotiatedMtu) ─────────────────|
   |── Write CCCD (Notify 활성화) ───────────────────▶
   |── Write CMD_OTA_START (0xFF01) ─────────────────▶
   |── Read (수신 준비 확인용 더미 read) ─────────────▶
   |   (200ms 대기)
   |── Write PDU#0 [idx=0][data][crc16] ──────────────▶ (write 완료 ACK 후 다음 패킷)
   |── Write PDU#1 [idx=1][data][crc16] ──────────────▶
   |   ...
   |── Write PDU#N (마지막, 0xFF 패딩) ────────────────▶
   |── Write CMD_OTA_END [lastIdx][~lastIdx][0x00×14] ▶
   |◀── Notify CMD_OTA_RESULT(0xFF06) [resultCode] ────| (실패 시에만 의미있게 검사, 성공은 END write 완료로 판정)
```

**RESULT(0xFF06) notify 바디**: `[opcode_lo=0x06][opcode_hi=0xFF][resultCode(1B)]`

| resultCode | 의미 |
|---|---|
| 0x00 | 성공 |
| 0x01 | 패킷 순번 오류(중복/누락) |
| 0x02 | 잘못된 OTA 패킷(커맨드/주소범위/길이 불량) |
| 0x03 | PDU CRC 불일치 |
| 0x04 | 플래시 쓰기 오류 |
| 0x05 | 마지막 PDU 유실 |
| 0x06 | 프로토콜 흐름 오류 |
| 0x07 | 펌웨어 CRC 체크 오류 |
| 0x08 | 업데이트 대상 버전이 현재보다 낮음 |
| 0x09 | PDU 길이 오류(16의 배수 아님) |
| 0x0A | 펌웨어 마크 오류 |
| 0x0B | 펌웨어 크기 오류 |
| 0x0C | 패킷 간 시간 초과 |
| 0x0D | OTA 전체 타임아웃 |
| 0x0E | 연결 종료로 인한 실패 |
| 0x80~0x89 | Secure Boot / 펌웨어 암호화 관련 오류 |

### 4.7 공연 제어 시퀀스 (v2.2 신규)

```
공연 시작 전  Android ── Write FF0A (CONTROL, SHOW_ENTER,  groupMask=0, effectIndex=n)   ──▶ 중계기 ─(방송)─▶ 전체 응원봉
공연 중       Android ── Write FF02 (EFFECT ...) ──▶ ...
일시정지      Android ── Write FF0A (CONTROL, PAUSE,       groupMask=0, effectIndex=n+1) ──▶ ... 현재 색에서 정지
재개          Android ── Write FF0A (CONTROL, RESUME,      groupMask=0, effectIndex=n+2) ──▶ ... 마지막 효과를 처음부터 재시작
공연 종료     Android ── Write FF0A (CONTROL, SHOW_EXIT,   groupMask=0, effectIndex=n+3) ──▶ ... 공연 모드 해제
전원 끄기     Android ── Write FF0A (CONTROL, SYSTEM_OFF,  groupMask=0, effectIndex=n+4) ──▶ ... 응원봉 전원 꺼짐
```
- 각 control 명령은 effectIndex를 새 값으로 보낸다(SDK는 자동). 직전 프레임과 20바이트가 같으면 중복으로 버려진다.
- 중요한 명령(SHOW_ENTER/SHOW_EXIT/SYSTEM_OFF)은 새 effectIndex로 재전송할 수 있다. 명령이 멱등이므로 이미 처리한 응원봉은 영향을 받지 않는다.
- groupMask로 PAUSE/RESUME/SYSTEM_OFF 대상을 그룹으로 좁힐 수 있다. 미배정 응원봉은 groupMask≠0 control을 무시한다.
- SYSTEM_OFF는 복구할 수 없으므로 앱은 사용자 확인 후에 보낸다.

---

## 5. 부록: 공통 규약

- **Write Type**: FF01/FF02/OTA PDU는 `Write Without Response`, FF03(게임 명령), FF09(Broadcasting), FF0A(Control)는 `Write With Response(Default)`, DIS/BAS/MAC/FF05/FF06은 `Read`.
- **CCCD 값**: Notify 활성화 `01 00`, 비활성화 `00 00` (Indication은 `02 00`, 본 서비스들에서는 미사용).
- **Little-Endian**: 모든 멀티바이트 정수 필드(u16/u32)는 LE.
- **msgType의 위상**: FF02/FF03/FF04/FF0A 모든 캐릭터리스틱이 offset 0에 `msgType`을 두는 동일한 설계 원칙을 따르되, FF02는 레이아웃 A(EFFECT/GROUP_SETUP 공용), FF03/FF04는 레이아웃 B(GAME_MODE 전용), FF0A는 레이아웃 C(CONTROL 전용)를 사용한다 — A와 C는 groupMask(offset 1-4)를, B와 C는 cmdIndex 이름을 공유한다(§2.7.2).
- **effectIndex의 위상**: 모든 20바이트 프레임(레이아웃 A/B/C, FF04) 공통으로 offset 17, u8에 위치하며(v2.1, v2.0은 u16 offset 18-19), 순수 dedup/시퀀스 용도일 뿐 메시지 종류 판별에는 관여하지 않는다. offset 18-19는 레이아웃 A에서 duration(u16), 레이아웃 B/FF04에서는 예약이다.
- **v2.0 → v2.1 호환성**: 구조체 크기(20바이트)는 같지만 offset 17 이후 의미가 바뀌었다 — 앱/응원봉/중계기를 함께 갱신해야 한다.
- **v2.1 → v2.2 호환성**: 레이아웃 A/B의 필드 배치는 그대로이고 msgType=3이 추가된다. 다만 **v2.1 응원봉은 msgType=3을 모르고 EFFECT로 해석**한다 — control 프레임을 받으면 groupMask를 그대로 읽고 offset 11의 effectType(예약 0)을 OFF로 해석해 **대상이 맞으면 소등**한다. SYSTEM_OFF도 v2.1 응원봉에서는 소등만 된다. 따라서 control(FF0A)은 응원봉과 중계기를 모두 v2.2로 갱신한 뒤에만 사용한다.

---

## 6. 버전 규칙 (v2.2부터)

응원봉/중계기 **펌웨어 버전은 프로토콜 버전과 일치**시킨다. 형식은 `<프로토콜 메이저>.<프로토콜 마이너><빌드>` — 프로토콜 V2.2의 펌웨어는 `2.200`에서 시작하고 수정 빌드마다 1씩 올린다(`2.201`, `2.202`, ...). 프로토콜이 올라가면 새 `.x00`부터 다시 시작한다(V2.3 → `2.300`). v2.2 이전 체계는 `1.0.x`이다.

- DIS Firmware Revision(0x2A26)에 펌웨어 버전 문자열이 그대로 노출된다(응원봉/중계기 공통).
- 앱은 **펌웨어 버전 ≥ `<프로토콜 버전>.x00`** 으로 지원 여부를 판단한다(예: Control(FF0A)은 `2.200` 이상). 비교는 문자열이 아니라 **메이저 → (마이너+빌드) 숫자 순서**로 한다(`2.200 < 2.201 < 2.300`, `1.0.7`은 `2.200`보다 낮음).
- SDK: `FirmwareVersion.parse()`, `DeviceInfo.supportsProtocol(2, 2)`, `Device.supportsProtocol(2, 2) { ... }`.
- **SDK 버전 표기**: SDK 아티팩트 버전(`2.200.0`)은 프로토콜 부분(2.2xx)을 펌웨어 버전과 맞춘다. 단, 앱이 알 수 있는 펌웨어 버전은 **연결된 기기 하나**뿐이다.
