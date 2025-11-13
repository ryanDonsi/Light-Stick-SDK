package com.lightstick.efx

import com.lightstick.types.LSEffectPayload
import java.io.File
import java.io.IOException

/**
 * High-level EFX container composed of a header ([EfxHeader]) and a body ([EfxBody]).
 *
 * This class provides convenient helpers to:
 *  - Serialize the current [header] and [body] into a compact EFX binary ([toByteArray]).
 *  - Persist the EFX binary to disk ([write]).
 *  - Decode EFX bytes or files back into an [Efx] instance ([read]).
 *
 * The wire format is assumed to be compatible with the SDK's EFX codec bridge.
 * The body payload frames are expected to be exactly 16 bytes each when encoded on the wire.
 *
 * @property header The parsed or user-constructed EFX header (metadata).
 * @property body   The parsed or user-constructed EFX body (ordered effect entries).
 *
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.EfxSamples.sampleCreateAndWrite
 * @sample com.lightstick.samples.EfxSamples.sampleReadFromFile
 */
class Efx(
    val header: EfxHeader,
    val body: EfxBody
) {

    /**
     * Serializes this EFX instance into a binary byte array.
     *
     * The method obtains timestamped frames from [body] (via [EfxBody.toFrames]) and
     * encodes them along with [header] fields into the EFX binary format.
     *
     * @return The encoded EFX as a new [ByteArray].
     * @throws IllegalArgumentException If any frame payload is not exactly 16 bytes.
     *
     * @sample com.lightstick.samples.EfxSamples.sampleEncodeToBytes
     */
    fun toByteArray(): ByteArray {
        // EfxBody.toFrames() must yield 16-byte payloads; the underlying codec expects 16B frames.
        val frames = body.toFrames()
        return com.lightstick.internal.api.Facade.efxSerializeFromEntries(
            header.musicId,
            frames
        )
    }

    /**
     * Writes the encoded EFX to the given [file].
     *
     * On success, the file will contain the exact bytes returned by [toByteArray].
     * Any I/O failure will propagate as an exception; the method returns `true` only when
     * the operation completes successfully.
     *
     * @param file Target file to write the EFX binary into.
     * @return `true` when the file has been written successfully.
     * @throws IOException If an I/O error occurs while writing to [file].
     * @throws IllegalArgumentException If [toByteArray] fails due to invalid frames.
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
         * with strictly validated 16-byte payloads.
         *
         * @param bytes EFX binary content to decode.
         * @return A fully materialized [Efx] object (header + body entries).
         * @throws IllegalArgumentException If any decoded frame is not 16 bytes in length.
         *
         * @sample com.lightstick.samples.EfxSamples.sampleDecodeFromBytes
         */
        fun read(bytes: ByteArray): Efx {
            val dec = com.lightstick.internal.api.Facade.efxDecode(bytes)
            val entries = dec.frames
                .sortedBy { it.first }
                .map { (ts, frame16) ->
                    require(frame16.size == 16) { "EFX frame must be 16 bytes (got ${frame16.size})" }
                    EfxEntry(timestampMs = ts, payload = LSEffectPayload.fromByteArray(frame16))
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
         * @param entries The effect entries to transform into (timestamp, 16B payload) pairs.
         * @return A new list of sorted pairs where `first = timestampMs` and `second = 16-byte payload`.
         * @throws IllegalArgumentException If any entry produces a payload that is not 16 bytes.
         *
         * @sample com.lightstick.samples.EfxSamples.sampleEntriesToFrames
         */
        fun toFrames(entries: List<EfxEntry>): List<Pair<Long, ByteArray>> =
            entries.sortedBy { it.timestampMs }.map { it.timestampMs to it.payload.toByteArray() }
    }
}
