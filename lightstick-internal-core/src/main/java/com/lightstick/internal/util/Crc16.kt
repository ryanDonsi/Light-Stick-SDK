package com.lightstick.internal.util

/**
 * Telink BLE OTA CRC-16/ARC (polynomial 0xA001, init 0xFFFF).
 *
 * Each OTA PDU must include a 2-byte CRC16 covering [index_lo, index_hi, data...].
 * Device firmware rejects packets whose CRC doesn't match (OTA FAIL code 2).
 */
internal object Crc16 {

    fun of(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        var crc = 0xFFFF
        val end = offset + length
        for (i in offset until end) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    fun of(vararg segments: ByteArray): Int {
        var crc = 0xFFFF
        for (seg in segments) {
            for (b in seg) {
                crc = crc xor (b.toInt() and 0xFF)
                repeat(8) {
                    crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }
}
