package com.lightstick.types

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Structured LightStick Effect payload (EFX spec v1.3) that encodes to an exact **16-byte** frame.
 *
 * Byte layout (indices in brackets, little-endian for u16):
 *  [0..1]  effectIndex (u16)  – Effect identifier or sequencing index
 *  [2..3]  ledMask     (u16)  – LED bitmask (0x0000 commonly means “all” in firmware)
 *  [4]     R           (u8)
 *  [5]     G           (u8)
 *  [6]     B           (u8)
 *  [7]     effectType  (u8)   – enum code, see [EffectType.code]
 *  [8..9]  durationMs  (u16)
 *  [10]    period      (u8)
 *  [11]    spf         (u8)
 *  [12]    randomColor (u8)
 *  [13]    randomDelay (u8)
 *  [14]    fade        (u8)
 *  [15]    syncIndex   (u8)
 *
 * Validation:
 * - Throws [IllegalArgumentException] if any field is outside its valid range.
 *
 * @param effectIndex Unsigned 16-bit index (0–65535).
 * @param ledMask Unsigned 16-bit LED bitmask.
 * @param color RGB color for this effect.
 * @param effectType Logical effect type; serialized as [EffectType.code].
 * @param durationMs Unsigned 16-bit duration in milliseconds.
 * @param period Unsigned byte (0–255), firmware-specific.
 * @param spf Unsigned byte (0–255), samples per frame or device-specific timing.
 * @param randomColor Unsigned byte flag/value, firmware-specific.
 * @param randomDelay Unsigned byte flag/value, firmware-specific.
 * @param fade Unsigned byte (0–255), firmware-specific fade parameter.
 * @param syncIndex Unsigned byte (0–255), used for device sync/slot.
 *
 * @throws IllegalArgumentException If any argument violates the documented ranges.
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.EfxSamples.sampleBuildPayload
 */
data class LSEffectPayload(
    val effectIndex: Int = 0,
    val ledMask: Int = 0x0000,
    val color: Color = Colors.WHITE,
    val effectType: EffectType = EffectType.ON,
    val durationMs: Int = 0,
    val period: Int = 0,
    val spf: Int = 100,
    val randomColor: Int = 0,
    val randomDelay: Int = 0,
    val fade: Int = 100,
    val syncIndex: Int = 0
) {

    init {
        require(effectIndex in 0..0xFFFF) { "effectIndex must be within 0..65535" }
        require(ledMask in 0..0xFFFF)     { "ledMask must be within 0..0xFFFF" }
        require(durationMs in 0..0xFFFF)  { "durationMs must be within 0..65535" }
        for ((name, v) in listOf(
            "period" to period,
            "spf" to spf,
            "randomColor" to randomColor,
            "randomDelay" to randomDelay,
            "fade" to fade,
            "syncIndex" to syncIndex
        )) require(v in 0..255) { "$name must be within 0..255" }
        require(color.r in 0..255 && color.g in 0..255 && color.b in 0..255) {
            "Color components must be 0..255"
        }
    }

    /**
     * Encodes this payload to a **16-byte** frame. Unsigned 16-bit fields are encoded
     * in little-endian. The effect type is serialized using [EffectType.code].
     *
     * @return A [ByteArray] of length 16 representing this payload.
     * @sample com.lightstick.samples.EfxSamples.sampleEncodePayload
     */
    fun toByteArray(): ByteArray {
        fun u8(v: Int) = (v and 0xFF).toByte()
        fun u16le(v: Int) = byteArrayOf(u8(v), u8(v ushr 8))

        val out = ByteArray(16)
        // [0..1] effectIndex
        u16le(effectIndex).copyInto(out, 0)
        // [2..3] ledMask
        u16le(ledMask).copyInto(out, 2)
        // [4..6] RGB
        out[4] = u8(color.r); out[5] = u8(color.g); out[6] = u8(color.b)
        // [7] effectType code (no reflection)
        out[7] = u8(effectType.code)
        // [8..9] durationMs
        u16le(durationMs).copyInto(out, 8)
        // [10..15] tail fields
        out[10] = u8(period)
        out[11] = u8(spf)
        out[12] = u8(randomColor)
        out[13] = u8(randomDelay)
        out[14] = u8(fade)
        out[15] = u8(syncIndex)
        return out
    }

    /**
     * Convenience factories for common effects.
     * Fields not provided remain at their defaults (e.g., `ledMask`, `durationMs`, `spf`, `fade`, `syncIndex`, `effectIndex`).
     */
    object Effects {

        /**
         * Constant ON effect.
         *
         * @param color LED color.
         * @param period Optional period byte (firmware-specific).
         * @param randomColor Optional randomColor byte.
         * @param randomDelay Optional randomDelay byte.
         * @return A new [LSEffectPayload] configured for [EffectType.ON].
         */
        @JvmStatic
        fun on(color: Color, period: Int = 0, randomColor: Int = 0, randomDelay: Int = 0) =
            LSEffectPayload(
                color = color,
                effectType = EffectType.ON,
                period = period,
                randomColor = randomColor,
                randomDelay = randomDelay
            )

        /** Fully OFF (black).
         *
         * @param period Optional period byte (firmware-specific).
         * @param randomDelay Optional randomDelay byte.
         * @return A new [LSEffectPayload] configured for [EffectType.OFF].
         */
        @JvmStatic
        fun off(period: Int = 0, randomDelay: Int = 0) =
            LSEffectPayload(
                color = Colors.BLACK,
                period = period,
                randomDelay = randomDelay,
                effectType = EffectType.OFF
            )

        /** Strobe effect.
         *
         * @param color LED color.
         * @param period Optional period byte (firmware-specific).
         * @param randomColor Optional randomColor byte.
         * @param randomDelay Optional randomDelay byte.
         * @return A new [LSEffectPayload] configured for [EffectType.STROBE].
         */
        @JvmStatic
        fun strobe(color: Color, period: Int, randomColor: Int = 0, randomDelay: Int = 0) =
            LSEffectPayload(
                color = color,
                effectType = EffectType.STROBE,
                period = period,
                randomColor = randomColor,
                randomDelay = randomDelay
            )

        /** Blink effect.
         *
         * @param color LED color.
         * @param period Optional period byte (firmware-specific).
         * @param randomColor Optional randomColor byte.
         * @param randomDelay Optional randomDelay byte.
         * @return A new [LSEffectPayload] configured for [EffectType.BLINK].
         */
        @JvmStatic
        fun blink(color: Color, period: Int, randomColor: Int = 0, randomDelay: Int = 0) =
            LSEffectPayload(
                color = color,
                effectType = EffectType.BLINK,
                period = period,
                randomColor = randomColor,
                randomDelay = randomDelay
            )

        /** Breathing effect.
         *
         * @param color LED color.
         * @param period Optional period byte (firmware-specific).
         * @param randomColor Optional randomColor byte.
         * @param randomDelay Optional randomDelay byte.
         * @return A new [LSEffectPayload] configured for [EffectType.BREATH].
         */
        @JvmStatic
        fun breath(color: Color, period: Int, randomColor: Int = 0, randomDelay: Int = 0) =
            LSEffectPayload(
                color = color,
                effectType = EffectType.BREATH,
                period = period,
                randomColor = randomColor,
                randomDelay = randomDelay
            )
    }

    companion object {

        /**
         * Reconstructs a payload from a **16-byte** serialized frame.
         * Mirrors [toByteArray] layout exactly.
         *
         * @param bytes A 16-byte array containing the serialized payload.
         * @return A deserialized [LSEffectPayload] instance.
         * @throws IllegalArgumentException If [bytes] length is not exactly 16.
         *
         * @sample com.lightstick.samples.EfxSamples.sampleDecodePayload
         */
        @JvmStatic
        fun fromByteArray(bytes: ByteArray): LSEffectPayload {
            require(bytes.size == 16) { "LSEffectPayload must be 16 bytes (got ${bytes.size})" }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            fun u16(): Int = bb.short.toInt() and 0xFFFF
            fun u8(): Int = bb.get().toInt() and 0xFF

            val effectIndex = u16()
            val ledMask = u16()
            val r = u8(); val g = u8(); val b = u8()
            val effectTypeCode = u8()
            val durationMs = u16()
            val period = u8()
            val spf = u8()
            val randomColor = u8()
            val randomDelay = u8()
            val fade = u8()
            val syncIndex = u8()

            return LSEffectPayload(
                effectIndex = effectIndex,
                ledMask = ledMask,
                color = Color(r, g, b),
                effectType = EffectType.fromCode(effectTypeCode),
                durationMs = durationMs,
                period = period,
                spf = spf,
                randomColor = randomColor,
                randomDelay = randomDelay,
                fade = fade,
                syncIndex = syncIndex
            )
        }
    }
}
