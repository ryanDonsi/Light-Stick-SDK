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
 * Updated for EFX v1.4 (20-byte payloads + default parameters).
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

        // ✅ Using default parameters - simple and clean!
        val header = EfxHeader(entryCount = body.size)

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

        // ✅ Simplified with default parameters
        val header = EfxHeader(
            musicId = 0x0000_0001,
            entryCount = body.size
        )

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

    // ========================================================================
    // 🔹 EfxHeader Creation Samples
    // ========================================================================

    /**
     * Create an EfxHeader using all default values.
     * This is the simplest way - all parameters use their defaults.
     * Used by: @sample sampleCreateHeaderAllDefaults
     */
    @JvmStatic
    fun sampleCreateHeaderAllDefaults(): EfxHeader {
        // ✅ All parameters use defaults!
        val header = EfxHeader()

        println("Header: magic=${header.magic}, version=0x${header.version.toString(16)}, " +
                "musicId=${header.musicId}, entryCount=${header.entryCount}")
        return header
    }

    /**
     * Create an EfxHeader specifying only what you need.
     * This is the most common use case.
     * Used by: @sample sampleCreateHeaderPartial
     */
    @JvmStatic
    fun sampleCreateHeaderPartial(): EfxHeader {
        // ✅ Only specify what you need
        val header = EfxHeader(
            musicId = 0x0000_0001,
            entryCount = 5
        )

        println("Header: magic=${header.magic}, version=0x${header.version.toString(16)}, " +
                "musicId=0x${header.musicId.toUInt().toString(16).uppercase()}, " +
                "entryCount=${header.entryCount}")
        return header
    }

    /**
     * Create an EfxHeader with only musicId (no entries yet).
     * Used by: @sample sampleCreateHeaderMusicOnly
     */
    @JvmStatic
    fun sampleCreateHeaderMusicOnly(): EfxHeader {
        val header = EfxHeader(musicId = 0x0000_ABCD)

        println("Header: musicId=0x${header.musicId.toUInt().toString(16).uppercase()}, " +
                "entryCount=${header.entryCount}")
        return header
    }

    /**
     * Create an EfxHeader with only entryCount (no music).
     * Used by: @sample sampleCreateHeaderEntriesOnly
     */
    @JvmStatic
    fun sampleCreateHeaderEntriesOnly(): EfxHeader {
        val header = EfxHeader(entryCount = 10)

        println("Header: musicId=${header.musicId}, entryCount=${header.entryCount}")
        return header
    }

    /**
     * Create an EfxHeader with explicit fields (verbose but complete control).
     * Used by: @sample sampleCreateHeader
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
        println("Header: magic=${header.magic}, version=0x${header.version.toString(16)}, " +
                "musicId=0x${header.musicId.toUInt().toString(16).uppercase()}")
        return header
    }

    /**
     * Create an EfxHeader with custom magic/version for testing or future versions.
     * Used by: @sample sampleCreateHeaderCustom
     */
    @JvmStatic
    fun sampleCreateHeaderCustom(): EfxHeader {
        val header = EfxHeader(
            magic = "EFX2",      // Custom magic
            version = 0x0200,    // Custom version
            musicId = 0x1234,
            entryCount = 5
        )

        println("Header: magic=${header.magic}, version=0x${header.version.toString(16)}")
        return header
    }

    /**
     * Alias for backward compatibility.
     * Prefer using EfxHeader() directly.
     * Used by: @sample sampleCreateHeaderDefaults
     */
    @JvmStatic
    fun sampleCreateHeaderDefaults(): EfxHeader {
        // ✅ New way - use constructor directly
        return EfxHeader(
            musicId = 0x0000_0001,
            entryCount = 5
        )
    }

    // ========================================================================
    // 🔹 Complete EFX Creation Examples
    // ========================================================================

    /**
     * Create a minimal EFX with no music and no entries.
     * Used by: @sample sampleCreateMinimalEfx
     */
    @JvmStatic
    fun sampleCreateMinimalEfx(): Efx {
        // ✅ All defaults - simplest possible!
        val header = EfxHeader()
        val body = EfxBody(emptyList())
        val efx = Efx(header, body)

        println("Minimal EFX: musicId=${efx.header.musicId}, entries=${efx.body.size}")
        return efx
    }

    /**
     * Create an EFX with music but no entries (placeholder).
     * Used by: @sample sampleCreateEfxWithMusicOnly
     */
    @JvmStatic
    fun sampleCreateEfxWithMusicOnly(musicId: Int): Efx {
        val header = EfxHeader(musicId = musicId)
        val body = EfxBody(emptyList())
        val efx = Efx(header, body)

        println("EFX with music: musicId=0x${musicId.toUInt().toString(16).uppercase()}")
        return efx
    }

    /**
     * Create an EFX with entries but no music.
     * Used by: @sample sampleCreateEfxWithEntriesOnly
     */
    @JvmStatic
    fun sampleCreateEfxWithEntriesOnly(): Efx {
        val entries = listOf(
            EfxEntry(0,   LSEffectPayload.Effects.on(color = Colors.RED)),
            EfxEntry(500, LSEffectPayload.Effects.off(transit = 20)),
        )
        val body = EfxBody(entries)
        val header = EfxHeader(entryCount = body.size)
        val efx = Efx(header, body)

        println("EFX without music: entries=${efx.body.size}")
        return efx
    }

    /**
     * Create a complete EFX with both music and entries.
     * Used by: @sample sampleCreateCompleteEfx
     */
    @JvmStatic
    fun sampleCreateCompleteEfx(musicId: Int): Efx {
        val entries = listOf(
            EfxEntry(0,    LSEffectPayload.Effects.on(color = Colors.WHITE)),
            EfxEntry(1000, LSEffectPayload.Effects.blink(color = Colors.BLUE, period = 10)),
            EfxEntry(3000, LSEffectPayload.Effects.breath(color = Colors.PINK, period = 15)),
            EfxEntry(5000, LSEffectPayload.Effects.off(transit = 30)),
        )
        val body = EfxBody(entries)
        val header = EfxHeader(
            musicId = musicId,
            entryCount = body.size
        )
        val efx = Efx(header, body)

        println("Complete EFX: musicId=0x${musicId.toUInt().toString(16).uppercase()}, " +
                "entries=${efx.body.size}")
        return efx
    }

    // ========================================================================
    // 🔹 LSEffectPayload Samples
    // ========================================================================

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
        println("Decoded: color=(${payload.color.r}, ${payload.color.g}, ${payload.color.b}), " +
                "type=${payload.effectType}")
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

    // ========================================================================
    // 🔹 MUSIC ID SAMPLES
    // ========================================================================

    /**
     * Compute MusicId from a local file.
     * Used by: @sample com.lightstick.efx.MusicId.fromFile
     */
    @JvmStatic
    fun sampleMusicIdFromFile(): Int {
        val musicDir = File(android.os.Environment.getExternalStorageDirectory(), "Music")
        val file = File(musicDir, "demo_song.mp3")
        val id = com.lightstick.efx.MusicId.fromFile(file)
        println("MusicId(file='${file.name}') = 0x${id.toUInt().toString(16).uppercase()}")
        return id
    }

    /**
     * Compute MusicId from a content Uri (e.g., Android MediaStore).
     * Used by: @sample com.lightstick.efx.MusicId.fromUri
     */
    @JvmStatic
    fun sampleMusicIdFromUri(context: Context, uri: Uri): Int {
        val id = com.lightstick.efx.MusicId.fromUri(context, uri)
        println("MusicId(uri=$uri) = 0x${id.toUInt().toString(16).uppercase()}")
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
        println("MusicId(stream='demo.bin') = 0x${id.toUInt().toString(16).uppercase()}")
        stream.close()
        return id
    }

    // ========================================================================
    // 🔹 PRACTICAL USE CASES
    // ========================================================================

    /**
     * Create an EFX synchronized with music.
     * Used by: @sample sampleCreateMusicSyncEfx
     */
    @JvmStatic
    fun sampleCreateMusicSyncEfx(context: Context, musicUri: Uri): Efx {
        // Calculate music ID
        val musicId = com.lightstick.efx.MusicId.fromUri(context, musicUri)

        // Create timeline synced to music
        val entries = listOf(
            EfxEntry(0,     LSEffectPayload.Effects.on(color = Colors.WHITE)),
            EfxEntry(500,   LSEffectPayload.Effects.strobe(color = Colors.RED, period = 5)),
            EfxEntry(2000,  LSEffectPayload.Effects.blink(color = Colors.BLUE, period = 10)),
            EfxEntry(4000,  LSEffectPayload.Effects.breath(color = Colors.PURPLE, period = 15)),
            EfxEntry(6000,  LSEffectPayload.Effects.off(transit = 50)),
        )

        val body = EfxBody(entries)
        val header = EfxHeader(
            musicId = musicId,
            entryCount = body.size
        )

        val efx = Efx(header, body)
        println("Music-synced EFX created: musicId=0x${musicId.toUInt().toString(16).uppercase()}")
        return efx
    }

    /**
     * Load existing EFX, modify it, and save.
     * Used by: @sample sampleModifyExistingEfx
     */
    @JvmStatic
    fun sampleModifyExistingEfx(inputFile: File, outputFile: File) {
        // Load existing EFX
        val efx = Efx.read(inputFile)

        // Add a new entry
        val newEntry = EfxEntry(
            timestampMs = 7000,
            payload = LSEffectPayload.Effects.blink(color = Colors.CYAN, period = 8)
        )

        val updatedEntries = efx.body.entries + newEntry
        val updatedBody = EfxBody(updatedEntries)

        // Update header
        val updatedHeader = efx.header.copy(entryCount = updatedBody.size)
        val updatedEfx = Efx(updatedHeader, updatedBody)

        // Save
        updatedEfx.write(outputFile)
        println("Modified EFX saved: ${updatedBody.size} entries")
    }
}