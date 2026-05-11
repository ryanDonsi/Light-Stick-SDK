package com.lightstick.internal.ble.ota

/**
 * Telink OTA 오퍼코드 (Opcode.java 기준).
 *
 * 전송 포맷: 2바이트 little-endian [LSB, MSB].
 *
 * Legacy Protocol 필수:
 *   CMD_OTA_VERSION (선택) → CMD_OTA_START → 데이터 PDU → CMD_OTA_END
 *
 * Extended Protocol 필수:
 *   CMD_OTA_FW_VERSION_REQ → CMD_OTA_FW_VERSION_RSP(notify) → CMD_OTA_START_EXT → 데이터 PDU → CMD_OTA_END
 *
 * 공통:
 *   CMD_OTA_RESULT (0xFF06): 디바이스가 OTA 완료/실패 시 notification으로 전송
 *   CMD_OTA_SET_FW_INDEX (0xFF80): 선택적으로 펌웨어 인덱스 지정
 */
internal enum class OtaOpcode(val code: UShort) {

    /** Legacy: slave의 현재 firmware 버전 조회 (선택적 사용). */
    CMD_OTA_VERSION(0xFF00u),

    /** Legacy: OTA 시작 명령. Legacy Protocol 사용 시 필수. */
    CMD_OTA_START(0xFF01u),

    /** All: OTA 종료 명령. Legacy/Extended 모두 사용. */
    CMD_OTA_END(0xFF02u),

    /** Extended: OTA 시작 명령. Extended Protocol 사용 시 필수. */
    CMD_OTA_START_EXT(0xFF03u),

    /** Extended: 버전 비교 요청 (master → slave). */
    CMD_OTA_FW_VERSION_REQ(0xFF04u),

    /** Extended: 버전 비교 응답 (slave → master, notification). */
    CMD_OTA_FW_VERSION_RSP(0xFF05u),

    /** All: OTA 결과 통지 (slave → master, notification). 성공/실패 1회 전송. */
    CMD_OTA_RESULT(0xFF06u),

    /** All: 펌웨어 인덱스 설정 (선택적). */
    CMD_OTA_SET_FW_INDEX(0xFF80u);

    /**
     * 2바이트 little-endian 배열로 인코딩: [LSB, MSB].
     */
    fun toBytes(): ByteArray = byteArrayOf(
        (code.toInt() and 0xFF).toByte(),
        ((code.toInt() shr 8) and 0xFF).toByte()
    )

    companion object {
        /**
         * 2바이트 little-endian 배열을 OtaOpcode로 파싱.
         */
        fun fromBytes(leTwoBytes: ByteArray): OtaOpcode? {
            require(leTwoBytes.size == 2) { "Opcode must be exactly 2 bytes (got ${leTwoBytes.size})" }
            val value = ((leTwoBytes[1].toInt() and 0xFF) shl 8) or (leTwoBytes[0].toInt() and 0xFF)
            val u = value.toUShort()
            return entries.firstOrNull { it.code == u }
        }
    }
}
