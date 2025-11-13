package com.lightstick.efx

import java.nio.charset.StandardCharsets
import com.lightstick.internal.api.Facade

/**
 * Represents the binary header section of an `.efx` file.
 *
 * Layout (LE):
 *  magic[4] ASCII ("EFX1"), version u16, reserved[3], musicId u32, entryCount u32
 *
 * Constraints:
 * - [magic] must be exactly 4 ASCII bytes.
 * - [version] fits into unsigned 16-bit.
 * - [reserved] must be 3 bytes.
 * - [musicId] and [entryCount] must be non-negative.
 *
 * @property magic 4-char ASCII identifier (e.g., "EFX1").
 * @property version Unsigned 16-bit version (stored as u16).
 * @property reserved Exactly 3 bytes reserved for future use.
 * @property musicId Deterministic 32-bit id (u32 in Int).
 * @property entryCount Number of effect entries in the body.
 *
 * @throws IllegalArgumentException If any constraint is violated.
 *
 * @sample com.lightstick.samples.EfxSamples.sampleCreateHeader
 * @sample com.lightstick.samples.EfxSamples.sampleMakeDefaults
 *
 * @since 1.0.0
 */
data class EfxHeader(
    val magic: String,
    val version: Int,
    val reserved: ByteArray,   // 3 bytes
    val musicId: Int,
    val entryCount: Int
) {
    init {
        require(magic.toByteArray(StandardCharsets.US_ASCII).size == 4) { "magic must be exactly 4 ASCII bytes" }
        require(version in 0..0xFFFF) { "version must fit in unsigned 16-bit" }
        require(reserved.size == 3) { "reserved must be 3 bytes" }
        require(musicId >= 0) { "musicId must be >= 0" }
        require(entryCount >= 0) { "entryCount must be >= 0" }
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
        private const val DEFAULT_MAGIC = "EFX1"
        private const val DEFAULT_VERSION = 0x0103
        private val DEFAULT_RESERVED = byteArrayOf(0, 0, 0)

        /**
         * Creates a header using SDK defaults for magic/version/reserved.
         *
         * Use this for **writing** new files. For **reading**, prefer [Efx.read] which
         * preserves the header values stored in the file.
         *
         * @param musicId Deterministic music id (u32 in Int).
         * @param entryCount Number of timeline entries to be written.
         * @return A new [EfxHeader] with default magic/version/reserved and provided fields.
         *
         * @sample com.lightstick.samples.EfxSamples.sampleMakeDefaults
         */
        @JvmStatic
        fun makeDefaults(musicId: Int, entryCount: Int): EfxHeader {
            val magic = try { Facade.efxMagic() } catch (_: Throwable) { DEFAULT_MAGIC }
            val version = try { Facade.efxVersion() } catch (_: Throwable) { DEFAULT_VERSION }
            val reserved = try { Facade.efxReserved() } catch (_: Throwable) { DEFAULT_RESERVED }
            val res = if (reserved.size == 3) reserved else DEFAULT_RESERVED
            return EfxHeader(magic, version, res, musicId, entryCount)
        }
    }
}
