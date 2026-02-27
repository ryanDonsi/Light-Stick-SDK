package com.lightstick.internal.ble.state

/**
 * 디바이스 상태 변경 이벤트
 *
 * DeviceStateManager에서 상태 변경 시 SharedFlow로 emit되는 이벤트 데이터.
 * StateFlow Conflation으로 인한 상태 누락 문제를 해결하기 위해 별도 이벤트 채널로 제공.
 *
 * @property mac   상태가 변경된 디바이스 MAC 주소
 * @property state 변경된 연결 상태
 */
data class InternalDeviceStateEvent(
    val mac:   String,
    val state: InternalConnectionState
)
