package com.lightstick.efx

import java.nio.charset.StandardCharsets

/**
 * Represents the binary header section of an `.efx` file.
 *
 * Layout (LE):
 *  magic[4] ASCII ("EFX1"), version u16, reserved[3], musicId u32, entryCount u32
 *
 * All parameters have sensible defaults:
 * - [magic] defaults to "EFX1"
 * - [version] defaults to 0x0103 (v1.3)
 * - [reserved] defaults to [0, 0, 0]
 * - [musicId] defaults to 0 (no music)
 * - [entryCount] defaults to 0 (empty body)
 *
 * Constraints:
 * - [magic] must be exactly 4 ASCII bytes.
 * - [version] fits into unsigned 16-bit.
 * - [reserved] must be 3 bytes.
 * - [musicId] and [entryCount] must be non-negative.
 *
 * @property magic 4-char ASCII identifier (default: "EFX1").
 * @property version Unsigned 16-bit version (default: 0x0103).
 * @property reserved Exactly 3 bytes reserved for future use (default: [0, 0, 0]).
 * @property musicId Deterministic 32-bit id (default: 0 = no music).
 * @property entryCount Number of effect entries in the body (default: 0 = empty).
 *
 * @throws IllegalArgumentException If any constraint is violated.
 *
 * @sample com.lightstick.samples.EfxSamples.sampleCreateHeaderDefaults
 * @sample com.lightstick.samples.EfxSamples.sampleCreateHeaderPartial
 *
 * @since 1.4.0
 */
data class EfxHeader(
    val magic: String = DEFAULT_MAGIC,
    val version: Int = DEFAULT_VERSION,
    val reserved: ByteArray = DEFAULT_RESERVED.clone(),
    val musicId: Int = 0,
    val entryCount: Int = 0
) {
    init {
        require(magic.toByteArray(StandardCharsets.US_ASCII).size == 4) {
            "magic must be exactly 4 ASCII bytes"
        }
        require(version in 0..0xFFFF) {
            "version must fit in unsigned 16-bit"
        }
        require(reserved.size == 3) {
            "reserved must be 3 bytes"
        }
        require(musicId >= 0) {
            "musicId must be >= 0"
        }
        require(entryCount >= 0) {
            "entryCount must be >= 0"
        }
    }

    // Ensure content-based equality for ByteArray field.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EfxHeader) return false
        return magic == other.magic &&
                version == other.version &&
                reserved.contentEquals(other.reserved) &&
                musicId == other.musicId &&
                entryCount == other.entryCount
    }

    override fun hashCode(): Int {
        var result = magic.hashCode()
        result = 31 * result + version
        result = 31 * result + reserved.contentHashCode()
        result = 31 * result + musicId
        result = 31 * result + entryCount
        return result
    }

    companion object {
        /** Default magic string for EFX files. */
        const val DEFAULT_MAGIC = "EFX1"

        /** Default version (1.4). */
        const val DEFAULT_VERSION = 0x0104

        /** Default reserved bytes. */
        @JvmField
        val DEFAULT_RESERVED = byteArrayOf(0, 0, 0)
    }
}