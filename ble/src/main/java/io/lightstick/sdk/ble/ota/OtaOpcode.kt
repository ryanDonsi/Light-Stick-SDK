package io.lightstick.sdk.ble.ota

/**
 * Represents all supported Telink OTA opcodes.
 *
 * Each opcode is 2 bytes (UShort), typically in little-endian format.
 */
enum class OtaOpcode(val code: UShort) {

    /** Legacy OTA Start (0xFF01) */
    START_LEGACY(0xFF01u),

    /** OTA End (0xFF02) */
    END(0xFF02u),

    /** OTA Check (0xFF03) – optional step in some devices */
    CHECK(0xFF03u),

    /** OTA Result response (0xFF06) – sent from slave to indicate result */
    RESULT(0xFF06u),

    /** Extended OTA Start (0xFF00) – used in newer OTA protocols with header */
    OTA_VERSION(0xFF00u),

    /** Extended OTA End (0xFF11) – not yet actively used */
    END_EXTENDED(0xFF11u);

    /**
     * Converts this opcode to a 2-byte little-endian byte array.
     *
     * @return ByteArray [lowByte, highByte] of the opcode
     */
    fun toBytes(): ByteArray {
        return byteArrayOf(
            (code.toInt() and 0xFF).toByte(),          // LSB
            ((code.toInt() shr 8) and 0xFF).toByte()   // MSB
        )
    }
}
