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
 */
internal object MusicIdProvider {

    fun fromFile(file: File): Int =
        hashToInt(file.readBytes())

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
