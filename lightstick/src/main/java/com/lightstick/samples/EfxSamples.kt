@file:Suppress(
    "UNUSED_PARAMETER",
    "UNUSED_VARIABLE",
    "UNUSED_EXPRESSION",
    "MemberVisibilityCanBePrivate",
    "CanBeVal",
    "MissingPermission"
)

package com.lightstick.samples

import android.content.Context
import android.net.Uri
import com.lightstick.efx.*
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import java.io.ByteArrayInputStream
import java.io.File

/**
 * EFX encode/decode usage samples referenced by KDoc @sample tags.
 */
object EfxSamples {
    /**
     * Create an EfxBody using a List constructor.
     */
    @JvmStatic
    fun sampleCreateBody(): EfxBody {
        val entries = listOf(
            EfxEntry(0,   LSEffectPayload.Effects.on(Colors.WHITE)),
            EfxEntry(150, LSEffectPayload.Effects.blink(Colors.BLUE, period = 10)),
            EfxEntry(300, LSEffectPayload.Effects.breath(Colors.PINK, period = 8))
        )
        val body = EfxBody(entries)
        println("Body size = ${body.size}")
        return body
    }

    /**
     * Create an EfxBody using the vararg convenience constructor.
     */
    @JvmStatic
    fun sampleCreateBodyVararg(): EfxBody {
        val body = EfxBody(
            EfxEntry(0,   LSEffectPayload.Effects.on(Colors.WHITE)),
            EfxEntry(200, LSEffectPayload.Effects.strobe(Colors.CYAN, period = 6)),
            EfxEntry(400, LSEffectPayload.Effects.breath(Colors.PURPLE, period = 8))
        )
        println("Body size = ${body.size}")
        return body
    }

    /**
     * Convert a body to raw frames (timestamp to 16-byte payload).
     */
    @JvmStatic
    fun sampleBodyToFrames(): List<Pair<Long, ByteArray>> {
        val body = sampleCreateBodyVararg()
        val frames = body.toFrames()
        println("Frames = ${frames.size}")
        // print first frame bytes (optional)
        frames.firstOrNull()?.let { (ts, bytes) ->
            println("First frame ts=$ts len=${bytes.size}")
        }
        return frames
    }

    /**
     * Creates an EFX (header+body) and writes it to a file.
     * Used by: @sample sampleCreateAndWrite
     */
    @JvmStatic
    fun sampleCreateAndWrite(file: File) {
        val entries = listOf(
            EfxEntry(0,   LSEffectPayload.Effects.on(Colors.WHITE)),
            EfxEntry(250, LSEffectPayload.Effects.blink(Colors.BLUE, period = 10)),
            EfxEntry(500, LSEffectPayload.Effects.breath(Colors.PINK, period = 8)),
        )
        val body = EfxBody(entries)
        val header = EfxHeader.makeDefaults(musicId = 0x0000_0000, entryCount = body.size)
        val efx = Efx(header, body)
        efx.write(file)
    }

    /**
     * Reads an EFX from a file and returns it.
     * Used by: @sample sampleReadFromFile
     */
    @JvmStatic
    fun sampleReadFromFile(file: File): Efx {
        val bytes = file.readBytes()
        val efx = Efx.read(bytes)
        println("EFX header: magic=${efx.header.magic}, ver=${efx.header.version}, entries=${efx.header.entryCount}")
        return efx
    }

    /**
     * Encodes an EFX object to raw bytes (for persistence or transfer).
     * Used by: @sample sampleEncodeToBytes
     */
    @JvmStatic
    fun sampleEncodeToBytes(): ByteArray {
        val entries = listOf(
            EfxEntry(0,   LSEffectPayload.Effects.on(Colors.WHITE)),
            EfxEntry(200, LSEffectPayload.Effects.blink(Colors.BLUE, period = 10)),
        )
        val body = EfxBody(entries)
        val header = EfxHeader.makeDefaults(musicId = 0x0000_0001, entryCount = body.size)
        val efx = Efx(header, body)
        return efx.toByteArray()
    }

    /**
     * Decodes an EFX object from raw bytes.
     * Used by: @sample sampleDecodeFromBytes
     */
    @JvmStatic
    fun sampleDecodeFromBytes(bytes: ByteArray): Efx {
        val efx = Efx.read(bytes)
        println("Decoded EFX entries: ${efx.body.size}")
        return efx
    }

    /**
     * Converts a list of [EfxEntry] to raw frames (timestamp to 16-byte payload).
     * Used by: @sample sampleEntriesToFrames
     */
    @JvmStatic
    fun sampleEntriesToFrames(): List<Pair<Long, ByteArray>> {
        val entries = listOf(
            EfxEntry(0,   LSEffectPayload.Effects.on(Colors.WHITE)),
            EfxEntry(150, LSEffectPayload.Effects.strobe(Colors.CYAN, period = 6)),
            EfxEntry(300, LSEffectPayload.Effects.breath(Colors.PURPLE, period = 8)),
        )
        // Either via EfxBody:
        val framesViaBody = EfxBody(entries).toFrames()

        // Or via Efx companion helper (if referenced):
        val framesViaCompanion = Efx.toFrames(entries)

        // Return one of them (both identical in layout).
        return framesViaBody
    }

    /**
     * Create a single EfxEntry at t=250ms with a BLUE blink payload.
     */
    @JvmStatic
    fun sampleCreateEntry(): EfxEntry {
        val payload = LSEffectPayload.Effects.blink(
            color = Colors.BLUE,
            period = 10
        )
        val entry = EfxEntry(
            timestampMs = 250L,
            payload = payload
        )
        println("Entry @ ${entry.timestampMs}ms")
        return entry
    }

    /**
     * Convert an EfxEntry to a raw (timestamp, 16-byte) frame pair.
     */
    @JvmStatic
    fun sampleEntryToFrame(): Pair<Long, ByteArray> {
        val entry = sampleCreateEntry()
        val frame = entry.toFrame()
        println("Frame ts=${frame.first}, len=${frame.second.size}")
        return frame
    }

    /**
     * Create an EfxHeader with explicit fields.
     * Used by: @sample com.lightstick.efx.EfxHeader (sampleCreateHeader)
     */
    @JvmStatic
    fun sampleCreateHeader(): EfxHeader {
        return EfxHeader(
            magic = "EFX1",
            version = 0x0103,
            reserved = byteArrayOf(0, 0, 0),
            musicId = 0x0000_0001,
            entryCount = 3
        )
    }

    /**
     * Create an EfxHeader using SDK defaults (recommended for writing).
     * Used by: @sample com.lightstick.efx.EfxHeader (sampleMakeDefaults)
     */
    @JvmStatic
    fun sampleMakeDefaults(): EfxHeader {
        return EfxHeader.makeDefaults(
            musicId = 0x0000_0000, // master (works independent of music file)
            entryCount = 2
        )
    }

    // ------------------------------------------------------------------------
    // 🔹 MUSIC ID SAMPLES (moved here for EFX consistency)
    // ------------------------------------------------------------------------

    /**
     * Compute MusicId from a raw stream (e.g., memory or network).
     */
    @JvmStatic
    fun sampleMusicIdFromStream(): Int {
        val demoBytes = "demo-music-data".toByteArray()
        val stream = ByteArrayInputStream(demoBytes)
        val id = MusicId.fromStream(stream, filenameHint = "demo.bin")
        println("MusicId(stream='demo.bin') = 0x${id.toUInt().toString(16)}")
        return id
    }

    /**
     * Compute MusicId from a local file.
     */
    @JvmStatic
    fun sampleMusicIdFromFile(): Int {
        val file = File("/tmp/demo_song.mp3")
        val id = MusicId.fromFile(file)
        println("MusicId(file='${file.name}') = 0x${id.toUInt().toString(16)}")
        return id
    }

    /**
     * Compute MusicId from a content Uri (e.g., Android MediaStore).
     */
    @JvmStatic
    fun sampleMusicIdFromUri(context: Context, uri: Uri): Int {
        val id = MusicId.fromUri(context, uri)
        println("MusicId(uri=$uri) = 0x${id.toUInt().toString(16)}")
        return id
    }


}
