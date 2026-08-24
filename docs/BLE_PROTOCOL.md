# 응원봉 ↔ Android BLE 통신 프로토콜

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
| LED Control Service (LCS) | `0001fe01-0000-1000-8000-00805f9800c4` | Custom | 색상/이펙트/그룹/게임 제어 (FF01~FF04) |
| GAP | `00001800-0000-1000-8000-00805f9b34fb` | Standard | Device Name (0x2A00) |
| DIS (Device Information) | `0000180a-0000-1000-8000-00805f9b34fb` | Standard | 모델명/펌웨어버전/제조사 |
| BAS (Battery Service) | `0000180f-0000-1000-8000-00805f9b34fb` | Standard | 배터리 잔량 |
| MAC Service (Custom) | `0001fe03-0000-1000-8000-00805f9800c4` | Custom | 내부 MAC 주소 문자열 |
| OTA Service (Telink) | `1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0` | Custom (Telink 표준) | 펌웨어 업데이트 |
| Concert Library (Optional) | `0001fe02-0000-1000-8000-00805f9800c4` | Custom | 배치 EFX 동기화 (선택 구현) |

---

## 2. LED Control Service (LCS) — `0001fe01`

응원봉 제어의 핵심 서비스. FF01~FF04 4개 캐릭터리스틱이 모두 이 서비스 하나에 속한다.

| 캐릭터리스틱 | UUID | Property | 크기 | 용도 |
|---|---|---|---|---|
| FF01 (Color) | `0001ff01-...-00805f9800c4` | Write No Response | 4B | 단순 RGB 컬러 |
| FF02 (Payload) | `0001ff02-...-00805f9800c4` | Write No Response | 20B | 이펙트 / 그룹 컨트롤 / 그룹 셋업 / 타임라인 프레임 |
| FF03 (Game Cmd) | `0001ff03-...-00805f9800c4` | Write | 20B | 게임 모드 명령 |
| FF04 (Game Result) | `0001ff04-...-00805f9800c4` | Notify | ≥11B (20B 프레임) | 게임 결과 통지 |

### 2.1 FF01 — Color Packet (4바이트)

가장 단순한 컨트롤 채널. 즉시 해당 RGB로 전환.

| Offset | 필드 | 타입 | 설명 |
|---|---|---|---|
| 0 | R | u8 | 0~255 |
| 1 | G | u8 | 0~255 |
| 2 | B | u8 | 0~255 |
| 3 | transition | u8 | 전환 파라미터 (펌웨어 종속) |

### 2.2 FF02 — Effect Payload (20바이트, `LE_LED_PATLOAD_T`, protocol v2.0)

`msgType`(offset 0) 하나가 프레임 전체의 **유일한 메시지 종류 판별자**다. `EFFECT`와 `GROUP_SETUP`은 아래와 동일한 레이아웃(레이아웃 A)을 공유하고, `GAME_MODE`는 FF03/FF04 전용의 다른 레이아웃(레이아웃 B, §3)을 사용한다 — 즉 msgType=1(GAME_MODE)인 프레임은 FF02가 아니라 FF03/FF04에서만 등장한다.

| Offset | 필드 | 타입 | 설명 |
|---|---|---|---|
| 0 | msgType | u8 | `0`=EFFECT, `1`=GAME_MODE(FF02에서는 미사용), `2`=GROUP_SETUP |
| 1-4 | groupMask | u32 LE | `0`=그룹 무시(단일/전체), `0xFFFFFFFF`=모든 그룹, bit(N-1)=1 → 그룹 N 타겟 (N=1~32, OR 조합 가능) |
| 5-7 | fgColor (R,G,B) | 3×u8 | 전경색 |
| 8-10 | bgColor (R,G,B) | 3×u8 | 배경색 |
| 11 | effectType | u8 | `0`=OFF, `1`=ON, `2`=STROBE, `3`=BLINK, `4`=BREATH |
| 12 | period | u8 | 0~255, 이펙트 주기 |
| 13 | spf | u8 | 0~255, frame당 sample 수 / 타이밍 파라미터 |
| 14 | randomColor | u8 | `0`=비활성, `1`=활성 |
| 15 | randomDelay | u8 | 0=없음, 1~255 = 10ms 단위 최대 랜덤 지연 |
| 16 | fade | u8 | 0~255, 페이드 파라미터 |
| 17 | broadcasting | u8 | `0`=단일 기기, `1`=주변 기기로 브로드캐스트 |
| 18-19 | effectIndex | u16 LE | 순수 dedup/시퀀스 번호. 메시지 종류 판별에는 관여하지 않음 |

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
| 11-17 | (미사용) | — | `0` |
| 18-19 | effectIndex | u16 LE | 하향 dedup용, 펌웨어가 메시지 판별에 사용하지 않음(0 가능) |

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
| 11-17 | (예약) | — | — |
| 18-19 | effectIndex | u16 LE | 이 경로에서는 미사용(신뢰성 있는 1회성 Notify), 항상 0 |

> **TEAM_CONFIRM(8)의 특성**: 개별 완드의 팀 락인은 802.15.4 상에서 각자 자신의 TEAM_CONFIRM으로 오르지만, 릴레이가 이를 내부적으로 집계만 하고 있다가 앱이 `TEAM_ASSIGN_END(9)`를 보낸 뒤 **팀당 1회, 집계된 인원수**로 FF04에 통지한다.

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
- 같은 재생 세션 내 모든 프레임은 동일한 `effectIndex` 값을 사용 — 새 세션(재로드/재개) 시작 시 값이 증가하여 응원봉이 이전 세션 프레임과 새 세션 프레임을 구분(dedup)할 수 있게 한다.
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

---

## 5. 부록: 공통 규약

- **Write Type**: FF01/FF02/OTA PDU는 `Write Without Response`, FF03(게임 명령)은 `Write With Response(Default)`, DIS/BAS/MAC은 `Read`.
- **CCCD 값**: Notify 활성화 `01 00`, 비활성화 `00 00` (Indication은 `02 00`, 본 서비스들에서는 미사용).
- **Little-Endian**: 모든 멀티바이트 정수 필드(u16/u32)는 LE.
- **msgType의 위상**: FF02/FF03/FF04 세 캐릭터리스틱 모두 offset 0에 `msgType`을 두는 동일한 설계 원칙을 따르되, FF02는 레이아웃 A(EFFECT/GROUP_SETUP 공용), FF03/FF04는 레이아웃 B(GAME_MODE 전용)를 사용 — 두 레이아웃은 offset 1 이후 필드 배치가 서로 다르다.
- **effectIndex의 위상**: 모든 20바이트 프레임 공통으로 offset 18-19에 위치하지만, 순수 dedup/시퀀스 용도일 뿐 메시지 종류 판별에는 전혀 관여하지 않는다.
