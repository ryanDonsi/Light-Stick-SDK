package com.lightstick.efx

import com.lightstick.types.LSEffectPayload

/**
 * One timeline entry in an EFX body: (timestamp in ms, 16B LED effect payload).
 *
 * Constraints:
 * - [timestampMs] must be >= 0
 * - [payload] encodes to exactly 16 bytes via [LSEffectPayload.toByteArray]
 *
 * @property timestampMs Playback timestamp in milliseconds (must be >= 0).
 * @property payload Structured 16-byte LED effect payload.
 *
 * @throws IllegalArgumentException If [timestampMs] is negative.
 *
 * @sample com.lightstick.samples.EfxSamples.sampleCreateEntry
 * @sample com.lightstick.samples.EfxSamples.sampleEntryToFrame
 *
 * @since 1.0.0
 */
data class EfxEntry(
    val timestampMs: Long,
    val payload: LSEffectPayload
) {
    init {
        require(timestampMs >= 0L) { "timestampMs must be >= 0" }
    }

    /**
     * Converts this entry into a raw (timestamp, frame16) pair.
     *
     * @return Pair of [timestampMs] and 16-byte payload.
     *
     * @sample com.lightstick.samples.EfxSamples.sampleEntryToFrame
     */
    fun toFrame(): Pair<Long, ByteArray> = timestampMs to payload.toByteArray()
}
