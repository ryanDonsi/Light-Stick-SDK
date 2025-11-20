package com.lightstick.efx

import com.lightstick.types.LSEffectPayload
import java.io.File

/**
 * EFX binary format: concatenated header + body sections.
 *
 * **Structure:**
 * - [header]: 20B metadata block (magic, version, musicId, entryCount, etc.)
 * - [body]: list of (timestamp_ms, 20B LED payload) pairs
 *
 * The EFX binary can be read from or written to files, then played on devices via
 * [com.lightstick.device.Controller.play].
 *
 * @property header Metadata describing the EFX file.
 * @property body   Timeline of effect entries.
 *
 * @sample com.lightstick.samples.EfxSamples.sampleCreateAndWrite
 * @sample com.lightstick.samples.EfxSamples.sampleReadFromFile
 *
 * @since 1.0.0
 */
data class Efx(
    val header: EfxHeader,
    val body: EfxBody
) {

    /**
     * Serializes this EFX into a binary [ByteArray].
     *
     * Layout:
     * - First 20 bytes = [header]
     * - Remaining bytes = [body] frames (timestamp + 20B payload per entry)
     *
     * @return Encoded EFX binary ready to be written to a file or transferred.
     *
     * @sample com.lightstick.samples.EfxSamples.sampleEncodeToBytes
     */
    fun toByteArray(): ByteArray {
        val frames = body.toFrames()
        return com.lightstick.internal.api.Facade.efxEncode(
            musicId = header.musicId,
            frames = frames
        )
    }

    /**
     * Writes this EFX to the specified [file].
     *
     * @param file Target file to write the encoded binary to.
     * @return true always (kept for consistency).
     *
     * @sample com.lightstick.samples.EfxSamples.sampleCreateAndWrite
     */
    fun write(file: File): Boolean {
        file.writeBytes(toByteArray())
        return true
    }

    companion object {

        /**
         * Decodes an EFX binary from memory into an [Efx] instance.
         *
         * The returned object preserves the exact header fields encountered in the binary:
         * [EfxHeader.magic], [EfxHeader.version], [EfxHeader.reserved], [EfxHeader.musicId],
         * and [EfxHeader.entryCount]. The body frames are converted into [EfxEntry] elements
         * with strictly validated 20-byte payloads.
         *
         * @param bytes EFX binary content to decode.
         * @return A fully materialized [Efx] object (header + body entries).
         * @throws IllegalArgumentException If any decoded frame is not 20 bytes in length.
         *
         * @sample com.lightstick.samples.EfxSamples.sampleDecodeFromBytes
         */
        fun read(bytes: ByteArray): Efx {
            val dec = com.lightstick.internal.api.Facade.efxDecode(bytes)
            val entries = dec.frames
                .sortedBy { it.first }
                .map { (ts, frame20) ->
                    require(frame20.size == 20) { "EFX frame must be 20 bytes (got ${frame20.size})" }
                    EfxEntry(timestampMs = ts, payload = LSEffectPayload.fromByteArray(frame20))
                }
            val body = EfxBody(entries)
            val header = EfxHeader(
                magic = dec.magic,
                version = dec.version,
                reserved = dec.reserved3,
                musicId = dec.musicId,
                entryCount = dec.entryCount
            )
            return Efx(header, body)
        }

        /**
         * Reads and decodes an EFX binary from a [file] path.
         *
         * This is a convenience wrapper that reads the entire file into memory and delegates
         * to [read] for parsing and validation.
         *
         * @param file Source file containing the EFX binary.
         * @return A decoded [Efx] instance.
         * @throws IOException If an I/O error occurs while reading [file].
         * @throws IllegalArgumentException If decoding fails due to invalid frame sizes.
         *
         * @sample com.lightstick.samples.EfxSamples.sampleReadFromFile
         */
        fun read(file: File): Efx = read(file.readBytes())

        /**
         * Converts a list of [EfxEntry] elements into timestamped wire frames suitable for
         * the codec layer. The entries are sorted by [EfxEntry.timestampMs] in ascending order.
         *
         * @param entries The effect entries to transform into (timestamp, 20B payload) pairs.
         * @return A new list of sorted pairs where `first = timestampMs` and `second = 20-byte payload`.
         * @throws IllegalArgumentException If any entry produces a payload that is not 20 bytes.
         *
         * @sample com.lightstick.samples.EfxSamples.sampleEntriesToFrames
         */
        @JvmStatic
        fun toFrames(entries: List<EfxEntry>): List<Pair<Long, ByteArray>> {
            return entries.sortedBy { it.timestampMs }.map { entry ->
                val frame = entry.payload.toByteArray()
                require(frame.size == 20) { "Payload must be 20 bytes (got ${frame.size})" }
                entry.timestampMs to frame
            }
        }
    }
}