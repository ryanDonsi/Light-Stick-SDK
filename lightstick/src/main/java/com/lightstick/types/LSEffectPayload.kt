package com.lightstick.types

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Structured LightStick Effect payload (EFX spec v1.4) that encodes to an exact **20-byte** frame.
 *
 * Byte layout (indices in brackets, little-endian for u16):
 *  [0..1]   effectIndex    (u16)  – Effect identifier or sequencing index
 *  [2..3]   ledMask        (u16)  – LED bitmask (0x0000 commonly means "all" in firmware)
 *  [4..6]   fgColor RGB    (3xu8) – Foreground color
 *  [7..9]   bgColor RGB    (3xu8) – Background color
 *  [10]     effectType     (u8)   – enum code, see [EffectType.code]
 *  [11..12] durationMs     (u16)
 *  [13]     period         (u8)
 *  [14]     spf            (u8)
 *  [15]     randomColor    (u8)   – 0 or 1 only (0=disabled, 1=enabled)
 *  [16]     randomDelay    (u8)   – 0~255: max random delay time (0=none, 1~255 = delay in units of 10ms)
 *  [17]     fade           (u8)
 *  [18]     broadcasting   (u8)   – 0 or 1 only (0=single device, 1=broadcast to nearby devices)
 *  [19]     syncIndex      (u8)
 *
 * Validation:
 * - Throws [IllegalArgumentException] if any field is outside its valid range.
 *
 * @param effectIndex Unsigned 16-bit index (0–65535), default: 0.
 * @param ledMask Unsigned 16-bit LED bitmask, default: 0x0000 (all LEDs).
 * @param color RGB foreground color for this effect, default: WHITE.
 * @param backgroundColor RGB background color for this effect, default: BLACK.
 * @param effectType Logical effect type; serialized as [EffectType.code], default: ON.
 * @param durationMs Unsigned 16-bit duration in milliseconds, default: 0.
 * @param period Unsigned byte (0–255), firmware-specific, default: 10.
 * @param spf Unsigned byte (0–255), samples per frame or device-specific timing, default: 100.
 * @param randomColor Random color flag (0=disabled, 1=enabled), default: 0.
 * @param randomDelay Random delay time in units of 10ms (0=none, 1~255=10ms~2550ms), default: 0.
 * @param fade Unsigned byte (0–255), firmware-specific fade parameter, default: 100.
 * @param broadcasting Broadcasting flag (0=single device, 1=broadcast to nearby devices), default: 0.
 * @param syncIndex Unsigned byte (0–255), used for device sync/slot, default: 0.
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
    val backgroundColor: Color = Colors.BLACK,
    val effectType: EffectType = EffectType.ON,
    val durationMs: Int = 0,
    val period: Int = 10,
    val spf: Int = 100,
    val randomColor: Int = 0,
    val randomDelay: Int = 0,
    val fade: Int = 100,
    val broadcasting: Int = 0,
    val syncIndex: Int = 0
) {

    init {
        require(effectIndex in 0..0xFFFF) { "effectIndex must be within 0..65535" }
        require(ledMask in 0..0xFFFF)     { "ledMask must be within 0..0xFFFF" }
        require(durationMs in 0..0xFFFF)  { "durationMs must be within 0..65535" }

        // 0~255 범위 체크
        for ((name, v) in listOf(
            "period" to period,
            "spf" to spf,
            "randomDelay" to randomDelay,
            "fade" to fade,
            "syncIndex" to syncIndex
        )) require(v in 0..255) { "$name must be within 0..255" }

        // 0 or 1만 허용
        require(randomColor in 0..1) { "randomColor must be 0 or 1 (got $randomColor)" }
        require(broadcasting in 0..1) { "broadcasting must be 0 or 1 (got $broadcasting)" }

        require(color.r in 0..255 && color.g in 0..255 && color.b in 0..255) {
            "Color components must be 0..255"
        }
        require(backgroundColor.r in 0..255 && backgroundColor.g in 0..255 && backgroundColor.b in 0..255) {
            "Background color components must be 0..255"
        }
    }

    /**
     * Encodes this payload to a **20-byte** frame. Unsigned 16-bit fields are encoded
     * in little-endian. The effect type is serialized using [EffectType.code].
     *
     * @return A [ByteArray] of length 20 representing this payload.
     * @sample com.lightstick.samples.EfxSamples.sampleEncodePayload
     */
    fun toByteArray(): ByteArray {
        fun u8(v: Int) = (v and 0xFF).toByte()
        fun u16le(v: Int) = byteArrayOf(u8(v), u8(v ushr 8))

        val out = ByteArray(20)
        // [0..1] effectIndex
        u16le(effectIndex).copyInto(out, 0)
        // [2..3] ledMask
        u16le(ledMask).copyInto(out, 2)
        // [4..6] fgColor RGB
        out[4] = u8(color.r); out[5] = u8(color.g); out[6] = u8(color.b)
        // [7..9] bgColor RGB
        out[7] = u8(backgroundColor.r); out[8] = u8(backgroundColor.g); out[9] = u8(backgroundColor.b)
        // [10] effectType code
        out[10] = u8(effectType.code)
        // [11..12] durationMs
        u16le(durationMs).copyInto(out, 11)
        // [13..19] tail fields
        out[13] = u8(period)
        out[14] = u8(spf)
        out[15] = u8(randomColor)
        out[16] = u8(randomDelay)
        out[17] = u8(fade)
        out[18] = u8(broadcasting)
        out[19] = u8(syncIndex)
        return out
    }

    /**
     * Convenience factories for common effects.
     * All fields can be customized; those not provided use sensible defaults.
     *
     * Note: Primary parameters (color, period, etc.) are listed first for ease of use,
     * while optional parameters (effectIndex, ledMask, spf, fade, etc.) follow with defaults.
     */
    object Effects {

        /**
         * Constant ON effect.
         *
         * **Primary Parameters** (commonly used):
         * @param color LED foreground color (required).
         * @param transit Transition time in units of 10ms (default: 0, mapped to period field).
         * @param randomColor Random color flag (0=disabled, 1=enabled, default: 0).
         * @param randomDelay Random delay in units of 10ms, 0~255 (default: 0).
         *
         * **Advanced Parameters** (optional):
         * @param effectIndex Effect identifier (default: 0).
         * @param ledMask LED bitmask (default: 0x0000 = all LEDs).
         * @param spf Samples per frame (default: 100).
         * @param fade Fade parameter (default: 100).
         * @param broadcasting Broadcasting flag (0=single device, 1=broadcast, default: 0).
         * @param syncIndex Sync index (default: 0).
         *
         * @return A new [LSEffectPayload] configured for [EffectType.ON].
         */
        @JvmStatic
        @JvmOverloads
        fun on(
            color: Color,
            transit: Int = 0,
            randomColor: Int = 0,
            randomDelay: Int = 0,
            effectIndex: Int = 0,
            ledMask: Int = 0x0000,
            spf: Int = 100,
            fade: Int = 100,
            broadcasting: Int = 0,
            syncIndex: Int = 0
        ) = LSEffectPayload(
            effectIndex = effectIndex,
            ledMask = ledMask,
            color = color,
            effectType = EffectType.ON,
            period = transit,
            spf = spf,
            randomColor = randomColor,
            randomDelay = randomDelay,
            fade = fade,
            broadcasting = broadcasting,
            syncIndex = syncIndex
        )

        /**
         * Fully OFF effect (black).
         *
         * **Primary Parameters** (commonly used):
         * @param transit Transition time in units of 10ms (default: 0, mapped to period field).
         * @param randomDelay Random delay in units of 10ms, 0~255 (default: 0).
         *
         * **Advanced Parameters** (optional):
         * @param effectIndex Effect identifier (default: 0).
         * @param spf Samples per frame (default: 100).
         * @param fade Fade parameter (default: 100).
         * @param broadcasting Broadcasting flag (0=single device, 1=broadcast, default: 0).
         * @param syncIndex Sync index (default: 0).
         *
         * @return A new [LSEffectPayload] configured for [EffectType.OFF].
         */
        @JvmStatic
        @JvmOverloads
        fun off(
            transit: Int = 0,
            randomDelay: Int = 0,
            effectIndex: Int = 0,
            spf: Int = 100,
            fade: Int = 100,
            broadcasting: Int = 0,
            syncIndex: Int = 0,
        ) = LSEffectPayload(
            effectIndex = effectIndex,
            color = Colors.BLACK,
            effectType = EffectType.OFF,
            period = transit,
            spf = spf,
            randomDelay = randomDelay,
            fade = fade,
            broadcasting = broadcasting,
            syncIndex = syncIndex
        )

        /**
         * Strobe effect.
         *
         * **Primary Parameters** (commonly used):
         * @param color LED foreground color (required).
         * @param backgroundColor LED background color (default: BLACK).
         * @param period Strobe period in units of 10ms (required).
         * @param randomColor Random color flag (0=disabled, 1=enabled, default: 0).
         * @param randomDelay Random delay in units of 10ms, 0~255 (default: 0).
         *
         * **Advanced Parameters** (optional):
         * @param effectIndex Effect identifier (default: 0).
         * @param broadcasting Broadcasting flag (0=single device, 1=broadcast, default: 0).
         * @param spf Samples per frame (default: 100).
         * @param fade Fade parameter (default: 100).
         * @param syncIndex Sync index (default: 0).
         * @param ledMask LED bitmask (default: 0x0000 = all LEDs).
         *
         * @return A new [LSEffectPayload] configured for [EffectType.STROBE].
         */
        @JvmStatic
        @JvmOverloads
        fun strobe(
            color: Color,
            backgroundColor: Color = Colors.BLACK,
            period: Int,
            randomColor: Int = 0,
            randomDelay: Int = 0,
            effectIndex: Int = 0,
            broadcasting: Int = 0,
            spf: Int = 100,
            fade: Int = 100,
            syncIndex: Int = 0,
            ledMask: Int = 0x0000
        ) = LSEffectPayload(
            effectIndex = effectIndex,
            ledMask = ledMask,
            color = color,
            backgroundColor = backgroundColor,
            effectType = EffectType.STROBE,
            period = period,
            spf = spf,
            randomColor = randomColor,
            randomDelay = randomDelay,
            fade = fade,
            broadcasting = broadcasting,
            syncIndex = syncIndex
        )

        /**
         * Blink effect.
         *
         * **Primary Parameters** (commonly used):
         * @param color LED foreground color (required).
         * @param backgroundColor LED background color (default: BLACK).
         * @param period Blink period in units of 10ms (required).
         * @param randomColor Random color flag (0=disabled, 1=enabled, default: 0).
         * @param randomDelay Random delay in units of 10ms, 0~255 (default: 0).
         *
         * **Advanced Parameters** (optional):
         * @param effectIndex Effect identifier (default: 0).
         * @param broadcasting Broadcasting flag (0=single device, 1=broadcast, default: 0).
         * @param spf Samples per frame (default: 100).
         * @param fade Fade parameter (default: 100).
         * @param syncIndex Sync index (default: 0).
         * @param ledMask LED bitmask (default: 0x0000 = all LEDs).
         *
         * @return A new [LSEffectPayload] configured for [EffectType.BLINK].
         */
        @JvmStatic
        @JvmOverloads
        fun blink(
            color: Color,
            backgroundColor: Color = Colors.BLACK,
            period: Int,
            randomColor: Int = 0,
            randomDelay: Int = 0,
            effectIndex: Int = 0,
            broadcasting: Int = 0,
            spf: Int = 100,
            fade: Int = 100,
            syncIndex: Int = 0,
            ledMask: Int = 0x0000
        ) = LSEffectPayload(
            effectIndex = effectIndex,
            ledMask = ledMask,
            color = color,
            backgroundColor = backgroundColor,
            effectType = EffectType.BLINK,
            period = period,
            spf = spf,
            randomColor = randomColor,
            randomDelay = randomDelay,
            fade = fade,
            broadcasting = broadcasting,
            syncIndex = syncIndex
        )

        /**
         * Breathing effect.
         *
         * **Primary Parameters** (commonly used):
         * @param color LED foreground color (required).
         * @param backgroundColor LED background color (default: BLACK).
         * @param period Breath period in units of 10ms (required).
         * @param randomColor Random color flag (0=disabled, 1=enabled, default: 0).
         * @param randomDelay Random delay in units of 10ms, 0~255 (default: 0).
         *
         * **Advanced Parameters** (optional):
         * @param effectIndex Effect identifier (default: 0).
         * @param broadcasting Broadcasting flag (0=single device, 1=broadcast, default: 0).
         * @param spf Samples per frame (default: 100).
         * @param fade Fade parameter (default: 100).
         * @param syncIndex Sync index (default: 0).
         * @param ledMask LED bitmask (default: 0x0000 = all LEDs).
         *
         * @return A new [LSEffectPayload] configured for [EffectType.BREATH].
         */
        @JvmStatic
        @JvmOverloads
        fun breath(
            color: Color,
            backgroundColor: Color = Colors.BLACK,
            period: Int,
            randomColor: Int = 0,
            randomDelay: Int = 0,
            effectIndex: Int = 0,
            broadcasting: Int = 0,
            spf: Int = 100,
            fade: Int = 100,
            syncIndex: Int = 0,
            ledMask: Int = 0x0000
        ) = LSEffectPayload(
            effectIndex = effectIndex,
            ledMask = ledMask,
            color = color,
            backgroundColor = backgroundColor,
            effectType = EffectType.BREATH,
            period = period,
            spf = spf,
            randomColor = randomColor,
            randomDelay = randomDelay,
            fade = fade,
            broadcasting = broadcasting,
            syncIndex = syncIndex
        )
    }

    companion object {

        /**
         * Reconstructs a payload from a **20-byte** serialized frame.
         * Mirrors [toByteArray] layout exactly.
         *
         * @param bytes A 20-byte array containing the serialized payload.
         * @return A deserialized [LSEffectPayload] instance.
         * @throws IllegalArgumentException If [bytes] length is not exactly 20.
         *
         * @sample com.lightstick.samples.EfxSamples.sampleDecodePayload
         */
        @JvmStatic
        fun fromByteArray(bytes: ByteArray): LSEffectPayload {
            require(bytes.size == 20) { "LSEffectPayload must be 20 bytes (got ${bytes.size})" }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            fun u16(): Int = bb.short.toInt() and 0xFFFF
            fun u8(): Int = bb.get().toInt() and 0xFF

            val effectIndex = u16()
            val ledMask = u16()
            val fgR = u8(); val fgG = u8(); val fgB = u8()
            val bgR = u8(); val bgG = u8(); val bgB = u8()
            val effectTypeCode = u8()
            val durationMs = u16()
            val period = u8()
            val spf = u8()
            val randomColor = u8()
            val randomDelay = u8()
            val fade = u8()
            val broadcasting = u8()
            val syncIndex = u8()

            return LSEffectPayload(
                effectIndex = effectIndex,
                ledMask = ledMask,
                color = Color(fgR, fgG, fgB),
                backgroundColor = Color(bgR, bgG, bgB),
                effectType = EffectType.fromCode(effectTypeCode),
                durationMs = durationMs,
                period = period,
                spf = spf,
                randomColor = randomColor,
                randomDelay = randomDelay,
                fade = fade,
                broadcasting = broadcasting,
                syncIndex = syncIndex
            )
        }
    }
}