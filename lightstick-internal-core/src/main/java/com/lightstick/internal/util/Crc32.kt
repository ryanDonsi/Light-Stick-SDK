package com.lightstick.internal.util

import java.util.zip.CRC32

/** CRC32 helper (Java CRC32 under the hood). Returns unsigned Int as Kotlin Int. */
object Crc32 {
    fun of(bytes: ByteArray): Int {
        val c = CRC32()
        c.update(bytes)
        // Java returns long (unsigned 32). We keep lower 32 bits in Int.
        return (c.value and 0xFFFF_FFFFL).toInt()
    }
}
