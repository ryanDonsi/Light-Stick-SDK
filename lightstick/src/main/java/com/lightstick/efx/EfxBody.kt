package com.lightstick.efx

/**
 * EFX body = ordered list of entries. Provides helpers to convert to raw frames
 * (timestamp in milliseconds, 20-byte effect payload).
 *
 * Construction:
 * - Use the primary constructor with a [List] of [EfxEntry], or
 * - Use the vararg convenience constructor for short inline definitions.
 *
 * @property entries Ordered [List] of [EfxEntry].
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.EfxSamples.sampleCreateBody
 * @sample com.lightstick.samples.EfxSamples.sampleCreateBodyVararg
 * @sample com.lightstick.samples.EfxSamples.sampleBodyToFrames
 */
data class EfxBody(
    val entries: List<EfxEntry>
) {
    /**
     * Convenience vararg constructor for small, inline body definitions.
     *
     * @sample com.lightstick.samples.EfxSamples.sampleCreateBodyVararg
     */
    constructor(vararg entries: EfxEntry) : this(entries.toList())

    /** Number of entries in this body. */
    val size: Int get() = entries.size

    /**
     * Converts the entries to raw frames expected by the internal façade.
     * The result is sorted by [EfxEntry.timestampMs] ascending.
     *
     * @return A list of pairs: `(timestampMs, 20-byte payload)`.
     * @sample com.lightstick.samples.EfxSamples.sampleBodyToFrames
     */
    fun toFrames(): List<Pair<Long, ByteArray>> =
        entries.sortedBy { it.timestampMs }
            .map { it.timestampMs to it.payload.toByteArray() }
}
