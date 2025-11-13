package com.lightstick.internal.ble.ota

/**
 * Telink-style OTA opcodes used by the device firmware.
 *
 * Each opcode is 2 bytes (UShort), typically transferred in **little-endian** order
 * on the wire (LSB first).
 *
 * This enum mirrors the legacy SDK values to keep backward compatibility at the
 * protocol level while living inside the internal module namespace.
 */
internal enum class OtaOpcode(val code: UShort) {

    /** Legacy OTA Start (0xFF01). */
    START_LEGACY(0xFF01u),

    /** OTA End (0xFF02). */
    END(0xFF02u),

    /** OTA Check (0xFF03) — optional step on some devices. */
    CHECK(0xFF03u),

    /** OTA Result response (0xFF06) — sent by the peripheral to indicate result. */
    RESULT(0xFF06u),

    /** Extended OTA header/version negotiation (0xFF00). */
    OTA_VERSION(0xFF00u),

    /** Extended OTA End (0xFF11) — reserved/not widely used. */
    END_EXTENDED(0xFF11u);

    /**
     * Encodes this opcode as a 2-byte **little-endian** array: [LSB, MSB].
     *
     * @return byte array of size 2 containing the opcode in little-endian order.
     */
    fun toBytes(): ByteArray = byteArrayOf(
        (code.toInt() and 0xFF).toByte(),          // LSB
        ((code.toInt() shr 8) and 0xFF).toByte()   // MSB
    )

    companion object {
        /**
         * Parses a 2-byte little-endian array into an [OtaOpcode], if known.
         *
         * @param leTwoBytes byte array of length 2, little-endian order (LSB, MSB).
         * @return matching [OtaOpcode] or `null` if unknown.
         * @throws IllegalArgumentException if [leTwoBytes] size is not exactly 2.
         */
        fun fromBytes(leTwoBytes: ByteArray): OtaOpcode? {
            require(leTwoBytes.size == 2) { "Opcode must be exactly 2 bytes (got ${leTwoBytes.size})" }
            val value = ((leTwoBytes[1].toInt() and 0xFF) shl 8) or (leTwoBytes[0].toInt() and 0xFF)
            val u = value.toUShort()
            return entries.firstOrNull { it.code == u }
        }
    }
}
