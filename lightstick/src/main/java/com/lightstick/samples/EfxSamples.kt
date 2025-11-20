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
 * Updated for EFX v1.4 (20-byte payloads).
 */
object EfxSamples {
    /**
     * Create an EfxBody using a List constructor.
     */
    @JvmStatic
    fun sampleCreateBody(): EfxBody {
        val entries = listOf(
            EfxEntry(0,   LSEffectPayload.Effects.on(color = Colors.WHITE)),
            EfxEntry(150, LSEffectPayload.Effects.blink(color = Colors.BLUE, period = 10)),
            EfxEntry(300, LSEffectPayload.Effects.breath(color = Colors.PINK, period = 8))
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
            EfxEntry(0,   LSEffectPayload.Effects.on(color = Colors.WHITE)),
            EfxEntry(200, LSEffectPayload.Effects.strobe(color = Colors.CYAN, period = 6)),
            EfxEntry(400, LSEffectPayload.Effects.breath(color = Colors.PURPLE, period = 8))
        )
        println("Body size = ${body.size}")
        return body
    }

    /**
     * Convert a body to raw frames (timestamp to 20-byte payload).
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
            EfxEntry(0,   LSEffectPayload.Effects.on(color = Colors.WHITE)),
            EfxEntry(250, LSEffectPayload.Effects.blink(color = Colors.BLUE, period = 10)),
            EfxEntry(500, LSEffectPayload.Effects.breath(color = Colors.PINK, period = 8)),
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
            EfxEntry(0,   LSEffectPayload.Effects.on(color = Colors.WHITE)),
            EfxEntry(200, LSEffectPayload.Effects.blink(color = Colors.BLUE, period = 10)),
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
     * Converts a list of [EfxEntry] to raw frames (timestamp to 20-byte payload).
     * Used by: @sample sampleEntriesToFrames
     */
    @JvmStatic
    fun sampleEntriesToFrames(): List<Pair<Long, ByteArray>> {
        val entries = listOf(
            EfxEntry(0,   LSEffectPayload.Effects.on(color = Colors.WHITE)),
            EfxEntry(150, LSEffectPayload.Effects.strobe(color = Colors.CYAN, period = 6)),
            EfxEntry(300, LSEffectPayload.Effects.breath(color = Colors.PURPLE, period = 8)),
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
     * Convert an EfxEntry to a raw (timestamp, 20-byte) frame pair.
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
     */
    @JvmStatic
    fun sampleCreateHeader(): EfxHeader {
        val header = EfxHeader(
            magic = "EFX1",
            version = 0x0104,
            reserved = byteArrayOf(0x00, 0x00, 0x00),
            musicId = 0x0000_0042,
            entryCount = 10
        )
        println("Header: magic=${header.magic}, version=0x${header.version.toString(16)}, musicId=${header.musicId}")
        return header
    }

    /**
     * Create an EfxHeader using defaults helper.
     * Used by: @sample sampleMakeDefaults
     */
    @JvmStatic
    fun sampleMakeDefaults(): EfxHeader {
        val header = EfxHeader.makeDefaults(
            musicId = 0x0000_0001,
            entryCount = 5
        )
        println("Header: magic=${header.magic}, version=0x${header.version.toString(16)}")
        return header
    }

    /**
     * Create an EfxHeader using defaults helper (alias for compatibility).
     */
    @JvmStatic
    fun sampleCreateHeaderDefaults(): EfxHeader {
        return sampleMakeDefaults()
    }

    /**
     * Build a custom LSEffectPayload.
     */
    @JvmStatic
    fun sampleBuildPayload(): LSEffectPayload {
        val payload = LSEffectPayload(
            effectIndex = 0,
            ledMask = 0x00FF,           // First 8 LEDs
            color = Colors.RED,
            backgroundColor = Colors.BLUE,
            effectType = com.lightstick.types.EffectType.STROBE,
            durationMs = 5000,
            period = 20,                // 200ms period
            spf = 100,
            randomColor = 1,            // Enable random color
            randomDelay = 10,           // Max 100ms random delay
            fade = 80,
            broadcasting = 1,           // Broadcast to nearby devices
            syncIndex = 0
        )
        println("Payload built: effectType=${payload.effectType}, period=${payload.period}")
        return payload
    }

    /**
     * Encode a payload to 20 bytes.
     */
    @JvmStatic
    fun sampleEncodePayload(): ByteArray {
        val payload = LSEffectPayload.Effects.on(color = Colors.GREEN, transit = 30)
        val bytes = payload.toByteArray()
        println("Encoded ${bytes.size} bytes")
        return bytes
    }

    /**
     * Decode a payload from 20 bytes.
     */
    @JvmStatic
    fun sampleDecodePayload(bytes: ByteArray): LSEffectPayload {
        val payload = LSEffectPayload.fromByteArray(bytes)
        println("Decoded: color=(${payload.color.r}, ${payload.color.g}, ${payload.color.b}), type=${payload.effectType}")
        return payload
    }

    /**
     * Demonstrate using primary parameters only (simple usage).
     */
    @JvmStatic
    fun sampleSimpleEffects() {
        // ON effect with just color
        val on = LSEffectPayload.Effects.on(color = Colors.RED)

        // ON with transition time
        val onSlow = LSEffectPayload.Effects.on(
            color = Colors.GREEN,
            transit = 50  // 500ms fade in
        )

        // BLINK with period
        val blink = LSEffectPayload.Effects.blink(
            color = Colors.BLUE,
            period = 100  // 1 second blink
        )

        // STROBE with background color
        val strobe = LSEffectPayload.Effects.strobe(
            color = Colors.WHITE,
            backgroundColor = Colors.RED,
            period = 5  // 50ms strobe
        )

        println("Simple effects created")
    }

    /**
     * Demonstrate using advanced parameters.
     */
    @JvmStatic
    fun sampleAdvancedEffects() {
        // ON with all parameters
        val advancedOn = LSEffectPayload.Effects.on(
            color = Colors.PURPLE,
            transit = 30,
            randomColor = 0,
            randomDelay = 0,
            effectIndex = 1,
            ledMask = 0x00FF,      // First 8 LEDs only
            spf = 100,
            fade = 80,
            broadcasting = 1,       // Broadcast to nearby
            syncIndex = 0
        )

        // BREATH with random features
        val randomBreath = LSEffectPayload.Effects.breath(
            color = Colors.CYAN,
            period = 100,
            randomColor = 1,        // Enable random color
            randomDelay = 20,       // Max 200ms random delay
            broadcasting = 1
        )

        println("Advanced effects created")
    }

    // ------------------------------------------------------------------------
    // 🔹 MUSIC ID SAMPLES
    // ------------------------------------------------------------------------

    /**
     * Compute MusicId from a local file.
     * Used by: @sample com.lightstick.efx.MusicId.fromFile
     */
    @JvmStatic
    fun sampleMusicIdFromFile(): Int {
        val musicDir = File(android.os.Environment.getExternalStorageDirectory(), "Music")
        val file = File(musicDir, "demo_song.mp3")
        val id = com.lightstick.efx.MusicId.fromFile(file)
        println("MusicId(file='${file.name}') = 0x${id.toUInt().toString(16)}")
        return id
    }

    /**
     * Compute MusicId from a content Uri (e.g., Android MediaStore).
     * Used by: @sample com.lightstick.efx.MusicId.fromUri
     */
    @JvmStatic
    fun sampleMusicIdFromUri(context: Context, uri: Uri): Int {
        val id = com.lightstick.efx.MusicId.fromUri(context, uri)
        println("MusicId(uri=$uri) = 0x${id.toUInt().toString(16)}")
        return id
    }

    /**
     * Compute MusicId from a raw stream (e.g., memory or network).
     * Used by: @sample com.lightstick.efx.MusicId.fromStream
     */
    @JvmStatic
    fun sampleMusicIdFromStream(): Int {
        val demoBytes = "demo-music-data".toByteArray()
        val stream = ByteArrayInputStream(demoBytes)
        val id = com.lightstick.efx.MusicId.fromStream(stream, filenameHint = "demo.bin")
        println("MusicId(stream='demo.bin') = 0x${id.toUInt().toString(16)}")
        stream.close()
        return id
    }
}