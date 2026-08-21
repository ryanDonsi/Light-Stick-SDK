package com.lightstick.types

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Structured LightStick Effect payload (protocol v2.0, `LE_LED_PATLOAD_T`) that encodes to an
 * exact **20-byte** frame written to FF02.
 *
 * Byte layout (indices in brackets, little-endian for multi-byte fields). Shared by
 * [MsgType.EFFECT] and [MsgType.GROUP_SETUP] — [MsgType.GAME_MODE] uses a different layout
 * entirely (see [MsgType]) and is rejected by [fromByteArray]:
 *  [0]      msgType        (u8)   – Sole message-type discriminator. Default [MsgType.EFFECT].
 *  [1..4]   groupMask      (u32)  – 0 = single/all (ignore group membership), 0xFFFFFFFF =
 *                                   every group, bit(N-1)=1 targets group N (N=1..32,
 *                                   combinable — see [Group]).
 *  [5..7]   fgColor RGB    (3xu8) – Foreground color
 *  [8..10]  bgColor RGB    (3xu8) – Background color
 *  [11]     effectType     (u8)   – enum code, see [EffectType.code]
 *  [12]     period         (u8)
 *  [13]     spf            (u8)
 *  [14]     randomColor    (u8)   – 0 or 1 only (0=disabled, 1=enabled)
 *  [15]     randomDelay    (u8)   – 0~255: max random delay time (0=none, 1~255 = delay in units of 10ms)
 *  [16]     fade           (u8)
 *  [17]     broadcasting   (u8)   – 0 or 1 only (0=single device, 1=broadcast to nearby devices)
 *  [18..19] effectIndex    (u16)  – Pure dedup/sequence number. Uninvolved in message-type
 *                                   discrimination.
 *
 * `groupMask` (a 32-bit bitmask) is what makes a payload group-targeted — there is no separate
 * "GroupControl" msgType. Single-device and group control are the exact same call shape, just a
 * different `groupMask`: pass one or more of [Group]'s `GRP1`..`GRP32` constants OR'd together
 * (or [Group.ALL_SINGLE] / [Group.ALL_GROUPS]) as the first constructor argument. `groupMask`
 * leads the constructor (default [Group.ALL_SINGLE], so it can be omitted for an ordinary
 * single-device effect) precisely so that group and non-group control read as one API:
 * ```kotlin
 * device.sendEffect(LSEffectPayload(Group.GRP1 or Group.GRP3, EffectType.ON, Colors.WHITE))
 * device.sendEffect(LSEffectPayload(effectType = EffectType.OFF, color = Colors.WHITE))
 * ```
 *
 * Validation:
 * - Throws [IllegalArgumentException] if any field is outside its valid range.
 *
 * @param groupMask 32-bit group bitmask (0..0xFFFFFFFF); see [Group] for the `GRP1`..`GRP32` /
 *        `ALL_SINGLE` / `ALL_GROUPS` constants. Default: [Group.ALL_SINGLE] (ignore group
 *        membership).
 * @param effectType Logical effect type; serialized as [EffectType.code], default: ON.
 * @param color RGB foreground color for this effect, default: WHITE.
 * @param backgroundColor RGB background color for this effect, default: BLACK.
 * @param msgType Message type (u8); see [MsgType]. Default: [MsgType.EFFECT]. Only
 *        [Device.sendGroupSetting] needs a non-default value here.
 * @param period Unsigned byte (0–255), firmware-specific, default: 10.
 * @param spf Unsigned byte (0–255), samples per frame or device-specific timing, default: 100.
 * @param randomColor Random color flag (0=disabled, 1=enabled), default: 0.
 * @param randomDelay Random delay time in units of 10ms (0=none, 1~255=10ms~2550ms), default: 0.
 * @param fade Unsigned byte (0–255), firmware-specific fade parameter, default: 100.
 * @param broadcasting Broadcasting flag (0=single device, 1=broadcast to nearby devices), default: 0.
 * @param effectIndex Unsigned 16-bit dedup/sequence number (0–65535), default: 0.
 *
 * @throws IllegalArgumentException If any argument violates the documented ranges.
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.EfxSamples.sampleBuildPayload
 */
data class LSEffectPayload(
    val groupMask: Long = Group.ALL_SINGLE,
    val effectType: EffectType = EffectType.ON,
    val color: Color = Colors.WHITE,
    val backgroundColor: Color = Colors.BLACK,
    val msgType: MsgType = MsgType.EFFECT,
    val period: Int = 10,
    val spf: Int = 100,
    val randomColor: Int = 0,
    val randomDelay: Int = 0,
    val fade: Int = 100,
    val broadcasting: Int = 0,
    val effectIndex: Int = 0
) {

    init {
        require(groupMask in 0L..0xFFFFFFFFL) { "groupMask must be within 0..0xFFFFFFFF" }
        require(effectIndex in 0..0xFFFF) { "effectIndex must be within 0..65535" }

        // 0~255 범위 체크
        for ((name, v) in listOf(
            "period" to period,
            "spf" to spf,
            "randomDelay" to randomDelay,
            "fade" to fade
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
     * Encodes this payload to a **20-byte** frame. Multi-byte fields are encoded in
     * little-endian. The effect type is serialized using [EffectType.code].
     *
     * @return A [ByteArray] of length 20 representing this payload.
     * @sample com.lightstick.samples.EfxSamples.sampleEncodePayload
     */
    fun toByteArray(): ByteArray {
        fun u8(v: Int) = (v and 0xFF).toByte()
        fun u16le(v: Int) = byteArrayOf(u8(v), u8(v ushr 8))
        fun u32le(v: Long) = byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte()
        )

        val out = ByteArray(20)
        // [0] msgType
        out[0] = u8(msgType.code)
        // [1..4] groupMask
        u32le(groupMask).copyInto(out, 1)
        // [5..7] fgColor RGB
        out[5] = u8(color.r); out[6] = u8(color.g); out[7] = u8(color.b)
        // [8..10] bgColor RGB
        out[8] = u8(backgroundColor.r); out[9] = u8(backgroundColor.g); out[10] = u8(backgroundColor.b)
        // [11] effectType code
        out[11] = u8(effectType.code)
        // [12..17] tail fields
        out[12] = u8(period)
        out[13] = u8(spf)
        out[14] = u8(randomColor)
        out[15] = u8(randomDelay)
        out[16] = u8(fade)
        out[17] = u8(broadcasting)
        // [18..19] effectIndex
        u16le(effectIndex).copyInto(out, 18)
        return out
    }

    /**
     * Convenience factories for common effects.
     * All fields can be customized; those not provided use sensible defaults.
     *
     * Note: Primary parameters (color, period, etc.) are listed first for ease of use,
     * while optional parameters (msgType, groupMask, spf, fade, etc.) follow with defaults.
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
         * @param msgType Message type (default: [MsgType.EFFECT]).
         * @param groupMask Group bitmask; default [Group.ALL_SINGLE]. See [Group].
         * @param spf Samples per frame (default: 100).
         * @param fade Fade parameter (default: 100).
         * @param broadcasting Broadcasting flag (0=single device, 1=broadcast, default: 0).
         * @param effectIndex Dedup/sequence number (default: 0).
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
            msgType: MsgType = MsgType.EFFECT,
            groupMask: Long = Group.ALL_SINGLE,
            spf: Int = 100,
            fade: Int = 100,
            broadcasting: Int = 0,
            effectIndex: Int = 0
        ) = LSEffectPayload(
            msgType = msgType,
            groupMask = groupMask,
            color = color,
            effectType = EffectType.ON,
            period = transit,
            spf = spf,
            randomColor = randomColor,
            randomDelay = randomDelay,
            fade = fade,
            broadcasting = broadcasting,
            effectIndex = effectIndex
        )

        /**
         * Fully OFF effect (black).
         *
         * **Primary Parameters** (commonly used):
         * @param transit Transition time in units of 10ms (default: 0, mapped to period field).
         * @param randomDelay Random delay in units of 10ms, 0~255 (default: 0).
         *
         * **Advanced Parameters** (optional):
         * @param msgType Message type (default: [MsgType.EFFECT]).
         * @param groupMask Group bitmask; default [Group.ALL_SINGLE]. See [Group].
         * @param spf Samples per frame (default: 100).
         * @param fade Fade parameter (default: 100).
         * @param broadcasting Broadcasting flag (0=single device, 1=broadcast, default: 0).
         * @param effectIndex Dedup/sequence number (default: 0).
         *
         * @return A new [LSEffectPayload] configured for [EffectType.OFF].
         */
        @JvmStatic
        @JvmOverloads
        fun off(
            transit: Int = 0,
            randomDelay: Int = 0,
            msgType: MsgType = MsgType.EFFECT,
            groupMask: Long = Group.ALL_SINGLE,
            spf: Int = 100,
            fade: Int = 100,
            broadcasting: Int = 0,
            effectIndex: Int = 0,
        ) = LSEffectPayload(
            msgType = msgType,
            groupMask = groupMask,
            color = Colors.BLACK,
            effectType = EffectType.OFF,
            period = transit,
            spf = spf,
            randomDelay = randomDelay,
            fade = fade,
            broadcasting = broadcasting,
            effectIndex = effectIndex
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
         * @param msgType Message type (default: [MsgType.EFFECT]).
         * @param groupMask Group bitmask; default [Group.ALL_SINGLE]. See [Group].
         * @param broadcasting Broadcasting flag (0=single device, 1=broadcast, default: 0).
         * @param spf Samples per frame (default: 100).
         * @param fade Fade parameter (default: 100).
         * @param effectIndex Dedup/sequence number (default: 0).
         *
         * @return A new [LSEffectPayload] configured for [EffectType.STROBE].
         */
        @JvmStatic
        @JvmOverloads
        fun strobe(
            period: Int,
            color: Color,
            backgroundColor: Color = Colors.BLACK,
            randomColor: Int = 0,
            randomDelay: Int = 0,
            msgType: MsgType = MsgType.EFFECT,
            broadcasting: Int = 0,
            spf: Int = 100,
            fade: Int = 100,
            effectIndex: Int = 0,
            groupMask: Long = Group.ALL_SINGLE
        ) = LSEffectPayload(
            msgType = msgType,
            groupMask = groupMask,
            color = color,
            backgroundColor = backgroundColor,
            effectType = EffectType.STROBE,
            period = period,
            spf = spf,
            randomColor = randomColor,
            randomDelay = randomDelay,
            fade = fade,
            broadcasting = broadcasting,
            effectIndex = effectIndex
        )

        /**
         * Blink effect.
         *
         * **Primary Parameters** (commonly used):
         * @param period Blink period in units of 10ms (required).
         * @param color LED foreground color (required).
         * @param backgroundColor LED background color (default: BLACK).
         * @param randomColor Random color flag (0=disabled, 1=enabled, default: 0).
         * @param randomDelay Random delay in units of 10ms, 0~255 (default: 0).
         *
         * **Advanced Parameters** (optional):
         * @param msgType Message type (default: [MsgType.EFFECT]).
         * @param groupMask Group bitmask; default [Group.ALL_SINGLE]. See [Group].
         * @param broadcasting Broadcasting flag (0=single device, 1=broadcast, default: 0).
         * @param spf Samples per frame (default: 100).
         * @param fade Fade parameter (default: 100).
         * @param effectIndex Dedup/sequence number (default: 0).
         *
         * @return A new [LSEffectPayload] configured for [EffectType.BLINK].
         */
        @JvmStatic
        @JvmOverloads
        fun blink(
            period: Int,
            color: Color,
            backgroundColor: Color = Colors.BLACK,
            randomColor: Int = 0,
            randomDelay: Int = 0,
            msgType: MsgType = MsgType.EFFECT,
            broadcasting: Int = 0,
            spf: Int = 100,
            fade: Int = 100,
            effectIndex: Int = 0,
            groupMask: Long = Group.ALL_SINGLE
        ) = LSEffectPayload(
            msgType = msgType,
            groupMask = groupMask,
            color = color,
            backgroundColor = backgroundColor,
            effectType = EffectType.BLINK,
            period = period,
            spf = spf,
            randomColor = randomColor,
            randomDelay = randomDelay,
            fade = fade,
            broadcasting = broadcasting,
            effectIndex = effectIndex
        )

        /**
         * Breathing effect.
         *
         * **Primary Parameters** (commonly used):
         * @param period Breath period in units of 10ms (required).
         * @param color LED foreground color (required).
         * @param backgroundColor LED background color (default: BLACK).
         * @param randomColor Random color flag (0=disabled, 1=enabled, default: 0).
         * @param randomDelay Random delay in units of 10ms, 0~255 (default: 0).
         *
         * **Advanced Parameters** (optional):
         * @param msgType Message type (default: [MsgType.EFFECT]).
         * @param groupMask Group bitmask; default [Group.ALL_SINGLE]. See [Group].
         * @param broadcasting Broadcasting flag (0=single device, 1=broadcast, default: 0).
         * @param spf Samples per frame (default: 100).
         * @param fade Fade parameter (default: 100).
         * @param effectIndex Dedup/sequence number (default: 0).
         *
         * @return A new [LSEffectPayload] configured for [EffectType.BREATH].
         */
        @JvmStatic
        @JvmOverloads
        fun breath(
            period: Int,
            color: Color,
            backgroundColor: Color = Colors.BLACK,
            randomColor: Int = 0,
            randomDelay: Int = 0,
            msgType: MsgType = MsgType.EFFECT,
            broadcasting: Int = 0,
            spf: Int = 100,
            fade: Int = 100,
            effectIndex: Int = 0,
            groupMask: Long = Group.ALL_SINGLE
        ) = LSEffectPayload(
            msgType = msgType,
            groupMask = groupMask,
            color = color,
            backgroundColor = backgroundColor,
            effectType = EffectType.BREATH,
            period = period,
            spf = spf,
            randomColor = randomColor,
            randomDelay = randomDelay,
            fade = fade,
            broadcasting = broadcasting,
            effectIndex = effectIndex
        )
    }

    companion object {

        /**
         * Reconstructs a payload from a **20-byte** serialized frame.
         * Mirrors [toByteArray] layout exactly.
         *
         * @param bytes A 20-byte array containing the serialized payload.
         * @return A deserialized [LSEffectPayload] instance.
         * @throws IllegalArgumentException If [bytes] length is not exactly 20, or if byte[0]
         *         (msgType) is [MsgType.GAME_MODE] — Game Mode frames use a different field
         *         layout (see [MsgType]) and are not valid [LSEffectPayload] frames.
         *
         * @sample com.lightstick.samples.EfxSamples.sampleDecodePayload
         */
        @JvmStatic
        fun fromByteArray(bytes: ByteArray): LSEffectPayload {
            require(bytes.size == 20) { "LSEffectPayload must be 20 bytes (got ${bytes.size})" }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            fun u16(): Int = bb.short.toInt() and 0xFFFF
            fun u8(): Int = bb.get().toInt() and 0xFF
            fun u32(): Long = bb.int.toLong() and 0xFFFFFFFFL

            val msgType = MsgType.fromCode(u8())
            require(msgType != MsgType.GAME_MODE) {
                "bytes is a Game Mode payload (msgType=GAME_MODE), not an LSEffectPayload frame"
            }
            val groupMask = u32()
            val fgR = u8(); val fgG = u8(); val fgB = u8()
            val bgR = u8(); val bgG = u8(); val bgB = u8()
            val effectTypeCode = u8()
            val period = u8()
            val spf = u8()
            val randomColor = u8()
            val randomDelay = u8()
            val fade = u8()
            val broadcasting = u8()
            val effectIndex = u16()

            return LSEffectPayload(
                msgType = msgType,
                groupMask = groupMask,
                color = Color(fgR, fgG, fgB),
                backgroundColor = Color(bgR, bgG, bgB),
                effectType = EffectType.fromCode(effectTypeCode),
                period = period,
                spf = spf,
                randomColor = randomColor,
                randomDelay = randomDelay,
                fade = fade,
                broadcasting = broadcasting,
                effectIndex = effectIndex
            )
        }
    }
}
