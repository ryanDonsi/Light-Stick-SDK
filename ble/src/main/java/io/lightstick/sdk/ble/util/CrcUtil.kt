package io.lightstick.sdk.ble.util

/**
 * Utility object for CRC (Cyclic Redundancy Check) computations used in Telink OTA protocol.
 *
 * Includes standard CRC-CCITT-FALSE algorithm and Telink-specific CRC16 variant used for verifying
 * OTA packet data integrity.
 *
 * This utility is critical when preparing or validating firmware update packets in BLE OTA workflows.
 */
object CrcUtil {

    /**
     * Computes a 16-bit CRC using the CRC-CCITT-FALSE polynomial.
     *
     * Polynomial: `0x1021`, Initial value: `0x0000`, No final XOR, No reflection.
     * This is the standard CRC used in many communication protocols.
     *
     * @param data Byte array input for which CRC is calculated
     * @return Computed CRC value as a 16-bit integer (0–65535)
     *
     * @sample
     * ```kotlin
     * val firmware = byteArrayOf(0x01, 0x02, 0x03)
     * val crc = CrcUtil.crc16(firmware)
     * Log.d("OTA", "Firmware CRC = ${crc.toString(16)}")
     * ```
     */
    fun crc16(data: ByteArray): Int {
        var crc = 0x0000
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
            }
        }
        return crc and 0xFFFF
    }

    /**
     * Telink-style CRC16 calculation for OTA packets.
     *
     * Used when sending 20-byte OTA packets, where the last 2 bytes are reserved for the CRC.
     * This algorithm differs from CCITT-FALSE and is specific to Telink SDK implementation.
     *
     * @param packet The byte array containing the OTA packet
     * @param offset Start index in the byte array
     * @param length Total length to be included in the CRC computation (excluding final 2 bytes)
     * @return 16-bit CRC value as integer
     *
     * @sample
     * ```kotlin
     * val packet = ByteArray(20) { it.toByte() }
     * val crc = CrcUtil.crc16Telink(packet)
     * ```
     */
    fun crc16Telink(packet: ByteArray, offset: Int = 0, length: Int = packet.size): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length - 2) { // last 2 bytes assumed to be CRC placeholder
            var b = packet[i].toInt() and 0xFF
            repeat(8) {
                val mix = (crc xor b) and 0x0001
                crc = (crc shr 1) xor (if (mix != 0) 0xA001 else 0)
                b = b shr 1
            }
        }
        return crc and 0xFFFF
    }

    /**
     * Converts a CRC integer value into a 2-byte array in little-endian format.
     *
     * @param crc CRC value as 16-bit integer (0–65535)
     * @return Byte array of size 2, formatted as [lowByte, highByte]
     *
     * @sample
     * ```kotlin
     * val crc = 0xA55A
     * val crcBytes = CrcUtil.crc16Bytes(crc)
     * // Result: [0x5A, 0xA5]
     * ```
     */
    fun crc16Bytes(crc: Int): ByteArray {
        return byteArrayOf(
            (crc and 0xFF).toByte(),
            ((crc shr 8) and 0xFF).toByte()
        )
    }
}
