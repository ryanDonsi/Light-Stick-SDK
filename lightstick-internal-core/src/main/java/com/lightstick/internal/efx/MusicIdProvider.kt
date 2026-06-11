package com.lightstick.internal.efx

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Provides deterministic 32-bit MusicId for any given file, stream, or URI.
 * Algorithm: SHA-256 → first 4 bytes → UInt32 (LE).
 *
 * Files larger than [LARGE_FILE_THRESHOLD_BYTES] are hashed over their first
 * [LARGE_FILE_READ_LIMIT_BYTES] only, to avoid OutOfMemoryError. Files at or
 * below the threshold are hashed over the full content, so existing Music IDs
 * remain unchanged.
 */
internal object MusicIdProvider {

    private const val LARGE_FILE_THRESHOLD_BYTES = 20L * 1024 * 1024
    private const val LARGE_FILE_READ_LIMIT_BYTES = 4 * 1024 * 1024

    fun fromFile(file: File): Int {
        if (file.length() <= LARGE_FILE_THRESHOLD_BYTES) {
            return hashToInt(file.readBytes())
        }
        return file.inputStream().use { input ->
            val buffer = ByteArray(LARGE_FILE_READ_LIMIT_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            hashToInt(if (read == buffer.size) buffer else buffer.copyOf(read))
        }
    }

    fun fromStream(stream: InputStream, filenameHint: String? = null): Int =
        hashToInt(stream.readBytes())

    fun fromUri(context: Context, uri: Uri): Int {
        context.contentResolver.openInputStream(uri)?.use { s ->
            return hashToInt(s.readBytes())
        }
        error("Failed to open stream for $uri")
    }

    private fun hashToInt(data: ByteArray): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val first4 = digest.take(4).toByteArray()
        return ByteBuffer.wrap(first4).order(ByteOrder.LITTLE_ENDIAN).int
    }
}
