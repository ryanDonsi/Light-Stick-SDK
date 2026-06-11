package com.lightstick.internal.efx

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random

class MusicIdProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun expectedId(data: ByteArray): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return ByteBuffer.wrap(digest, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun writeFile(name: String, sizeBytes: Int): File {
        val file = tempFolder.newFile(name)
        val random = Random(seed = sizeBytes.toLong())
        file.outputStream().use { out ->
            val chunk = ByteArray(1024 * 1024)
            var remaining = sizeBytes
            while (remaining > 0) {
                val n = minOf(chunk.size, remaining)
                random.nextBytes(chunk)
                out.write(chunk, 0, n)
                remaining -= n
            }
        }
        return file
    }

    @Test
    fun fromFile_smallFile_hashesFullContent() {
        val file = writeFile("small.mp3", 64 * 1024)
        assertEquals(expectedId(file.readBytes()), MusicIdProvider.fromFile(file))
    }

    @Test
    fun fromFile_fileAtThreshold_hashesFullContent() {
        val file = writeFile("threshold.mp3", 20 * 1024 * 1024)
        assertEquals(expectedId(file.readBytes()), MusicIdProvider.fromFile(file))
    }

    @Test
    fun fromFile_largeFile_hashesFirst4MbOnly() {
        val file = writeFile("large.mp3", 21 * 1024 * 1024)
        val first4Mb = file.inputStream().use { it.readNBytes(4 * 1024 * 1024) }
        assertEquals(expectedId(first4Mb), MusicIdProvider.fromFile(file))
    }

    @Test
    fun fromFile_largeFile_matchesFromStreamWithLimit() {
        // The app's interim workaround feeds the first 4 MB through fromStream;
        // the SDK implementation must produce the identical ID.
        val file = writeFile("large2.mp3", 21 * 1024 * 1024)
        val first4Mb = file.inputStream().use { it.readNBytes(4 * 1024 * 1024) }
        assertEquals(
            MusicIdProvider.fromStream(first4Mb.inputStream()),
            MusicIdProvider.fromFile(file)
        )
    }
}
