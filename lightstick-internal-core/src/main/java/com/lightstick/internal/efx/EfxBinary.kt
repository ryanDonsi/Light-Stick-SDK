package com.lightstick.internal.efx

import com.lightstick.internal.util.Crc32
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-level EFX v1.3 binary codec (encode/decode).
 *
 * Header (little-endian):
 *   magic[4] = "EFX1"
 *   version  = 0x0103 (u16)
 *   reserved3= 0x00 0x00 0x00
 *   musicId  = u32
 *   entryCnt = u32
 *
 * Entry:
 *   timestampMs = u32
 *   frame16[16]
 *
 * Tail:
 *   fileCrc32 = u32 of (header..entries)
 */
internal object EfxBinary {

    // Defaults (source of truth for defaults)
    const val MAGIC = "EFX1"
    const val VERSION = 0x0103
    val RESERVED3 = byteArrayOf(0x00, 0x00, 0x00)

    data class Parsed(
        val magic: String,
        val version: Int,
        val reserved3: ByteArray,
        val musicId: Int,
        val entryCount: Int,
        val frames: List<Pair<Long, ByteArray>>
    )

    /** Encode with explicit header. */
    fun encode(
        magic: String,
        version: Int,
        reserved3: ByteArray,
        musicId: Int,
        frames: List<Pair<Long, ByteArray>>
    ): ByteArray {
        require(magic.length == 4) { "magic must be 4 chars" }
        require(version in 0..0xFFFF) { "version must fit u16" }
        require(reserved3.size == 3) { "reserved3 must be 3 bytes" }
        frames.forEach { (_, f) -> require(f.size == 16) { "frame must be 16 bytes" } }

        val sorted = frames.sortedBy { it.first }
        val bodySize = sorted.size * (4 + 16)
        val headerSize = 4 + 2 + 3 + 4 + 4
        val totalNoCrc = headerSize + bodySize
        val total = totalNoCrc + 4

        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)

        // header
        buf.put(magic.toByteArray(Charsets.US_ASCII))  // 4
        buf.putShort(version.toShort())                // 2
        buf.put(reserved3)                             // 3
        buf.putInt(musicId)                            // 4
        buf.putInt(sorted.size)                        // 4

        // entries
        for ((ts, frame) in sorted) {
            require(ts in 0..0xFFFF_FFFFL) { "timestamp out of u32 range: $ts" }
            buf.putInt(ts.toInt())
            buf.put(frame)
        }

        // CRC32 over (header + entries)
        val crcArea = ByteArray(totalNoCrc)
        buf.position(0)
        buf.get(crcArea)
        val crc = Crc32.of(crcArea)

        buf.putInt(crc) // tail
        return buf.array()
    }

    /** Encode using internal defaults for magic/version/reserved. */
    fun encodeWithDefaults(
        musicId: Int,
        frames: List<Pair<Long, ByteArray>>
    ): ByteArray = encode(
        magic = MAGIC,
        version = VERSION,
        reserved3 = RESERVED3,
        musicId = musicId,
        frames = frames
    )

    /** Decode and fully verify the file (including CRC). */
    fun decode(bytes: ByteArray): Parsed {
        require(bytes.size >= (4 + 2 + 3 + 4 + 4 + 4)) { "EFX: too small" }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magicBytes = ByteArray(4).also { buf.get(it) }
        val magic = String(magicBytes, Charsets.US_ASCII)
        val version = buf.short.toInt() and 0xFFFF
        val reserved = ByteArray(3).also { buf.get(it) }
        val musicId = buf.int
        val entryCount = buf.int
        require(entryCount >= 0) { "invalid entryCount" }

        val needBody = entryCount * (4 + 16)
        val withoutCrc = 4 + 2 + 3 + 4 + 4 + needBody
        require(bytes.size == withoutCrc + 4) {
            "EFX: size mismatch (has=${bytes.size}, want=${withoutCrc + 4})"
        }

        val frames = ArrayList<Pair<Long, ByteArray>>(entryCount)
        repeat(entryCount) {
            val ts = (buf.int.toLong() and 0xFFFF_FFFFL)
            val frame = ByteArray(16).also { buf.get(it) }
            frames += ts to frame
        }

        val crcFile = buf.int
        val crcArea = bytes.copyOfRange(0, withoutCrc)
        val crcCalc = Crc32.of(crcArea)
        require(crcFile == crcCalc) { "EFX: CRC32 mismatch (file=$crcFile, calc=$crcCalc)" }

        return Parsed(
            magic = magic,
            version = version,
            reserved3 = reserved,
            musicId = musicId,
            entryCount = entryCount,
            frames = frames
        )
    }
}
