package com.lightstick.efx

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * Deterministic 32-bit Music ID generator (unsigned 32-bit represented as [Int]).
 *
 * This utility computes a stable, reproducible identifier for a music file or stream.
 * Internally, the SDK uses SHA-256 hashing, taking the first 4 bytes of the digest
 * as a little-endian unsigned 32-bit integer.
 *
 * The same file or stream content will always yield the same Music ID.
 * This ID is used inside `.efx` headers ([EfxHeader.musicId]) to associate
 * LED effect sequences with specific tracks.
 *
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.EfxSamples.sampleMusicIdFromFile
 * @sample com.lightstick.samples.EfxSamples.sampleMusicIdFromUri
 */
object MusicId {

    /**
     * Generates a deterministic Music ID from a given file.
     *
     * The file’s binary contents are read and hashed; the first 4 bytes
     * of the resulting SHA-256 digest are used to produce a 32-bit ID.
     *
     * @param file The source [File] containing the music data.
     * @return A stable 32-bit Music ID represented as an [Int].
     * @throws java.io.IOException If the file cannot be read.
     *
     * @sample com.lightstick.samples.EfxSamples.sampleMusicIdFromFile
     */
    @JvmStatic
    fun fromFile(file: File): Int =
        com.lightstick.internal.api.Facade.musicIdFromFile(file)

    /**
     * Generates a deterministic Music ID from an input stream.
     *
     * The stream’s binary data is hashed and converted into a 32-bit ID
     * using the same SHA-256 → LE uint32 method as [fromFile].
     *
     * An optional [filenameHint] may be used for future extensions or
     * consistent file-based hashing when the original name is known.
     *
     * @param stream The [InputStream] providing music data.
     * @param filenameHint Optional filename for reference or deterministic mapping.
     * @return A stable 32-bit Music ID represented as an [Int].
     * @throws java.io.IOException If an error occurs while reading from [stream].
     *
     * @sample com.lightstick.samples.EfxSamples.sampleMusicIdFromStream
     */
    @JvmStatic
    fun fromStream(stream: InputStream, filenameHint: String? = null): Int =
        com.lightstick.internal.api.Facade.musicIdFromStream(stream, filenameHint)

    /**
     * Generates a deterministic Music ID from a [Uri]-based source.
     *
     * This method allows using Android’s [ContentResolver] to open and
     * hash the contents of files selected via SAF, MediaStore, or other content providers.
     *
     * @param context The [Context] used to resolve the given [uri].
     * @param uri The [Uri] pointing to a readable music resource.
     * @return A stable 32-bit Music ID represented as an [Int].
     * @throws java.io.IOException If the content cannot be opened or read.
     * @throws SecurityException If reading from [uri] is not permitted.
     *
     * @sample com.lightstick.samples.EfxSamples.sampleMusicIdFromUri
     */
    @JvmStatic
    fun fromUri(context: Context, uri: Uri): Int =
        com.lightstick.internal.api.Facade.musicIdFromUri(context, uri)
}
