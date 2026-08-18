package com.lightstick.types

import com.lightstick.group.GroupPalette
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Structured LightStick Effect payload (protocol v3, `LE_LED_PATLOAD_T`) that encodes to an
 * exact **20-byte** frame written to FF02.
 *
 * Byte layout (indices in brackets, little-endian for u16). Shared by [MsgType.MUSIC],
 * [MsgType.GROUP_SETUP] and [MsgType.GROUP_CONTROL] — [MsgType.GAME_MODE] uses a different
 * layout entirely (see [MsgType]) and is rejected by [fromByteArray]:
 *  [0]      msgType        (u8)   – Sole message-type discriminator. Default [MsgType.MUSIC].
 *  [1]      groupId        (u8)   – 0 = unassigned/all, 1..20 = group (see [com.lightstick.group.GroupPalette]).
 *  [2..4]   fgColor RGB    (3xu8) – Foreground color
 *  [5..7]   bgColor RGB    (3xu8) – Background color
 *  [8]      effectType     (u8)   – enum code, see [EffectType.code]
 *  [9..10]  durationMs     (u16)
 *  [11]     period         (u8)
 *  [12]     spf            (u8)
 *  [13]     randomColor    (u8)   – 0 or 1 only (0=disabled, 1=enabled)
 *  [14]     randomDelay    (u8)   – 0~255: max random delay time (0=none, 1~255 = delay in units of 10ms)
 *  [15]     fade           (u8)
 *  [16]     broadcasting   (u8)   – 0 or 1 only (0=single device, 1=broadcast to nearby devices)
 *  [17]     reserved       (u8)   – Always 0.
 *  [18..19] effectIndex    (u16)  – Pure dedup/sequence number. Uninvolved in message-type
 *                                   discrimination (contrast with v2's offset-0 effectIndex).
 *
 * As of protocol v3, `msgType` moved to offset 0 as the single discriminator and `effectIndex`
 * moved to offset 18-19 as a pure sequence/dedup number — replacing v2's two-level scheme
 * (effectIndex at offset 0-1 doubling as a game/non-game gate, msgType at offset 2) and v1's
 * `mode`/`ledMask` fields. [Device.loadTimeline] auto-manages `effectIndex` the same way it
 * used to auto-manage the old `syncIndex` field this replaces — most callers never set it by
 * hand.
 *
 * Validation:
 * - Throws [IllegalArgumentException] if any field is outside its valid range.
 *
 * @param msgType Message type (u8); see [MsgType]. Default: [MsgType.MUSIC].
 * @param groupId Target group: 0 = unassigned/all, 1..20 = a single group. Default: 0.
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
 * @param effectIndex Unsigned 16-bit dedup/sequence number (0–65535), default: 0.
 *
 * @throws IllegalArgumentException If any argument violates the documented ranges.
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.EfxSamples.sampleBuildPayload
 */
data class LSEffectPayload(
    val msgType: MsgType = MsgType.MUSIC,
    val groupId: Int = 0,
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
    val effectIndex: Int = 0
) {

    init {
        require(groupId in 0..20)         { "groupId must be within 0..20" }
        require(durationMs in 0..0xFFFF)  { "durationMs must be within 0..65535" }
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
        // [0] msgType
        out[0] = u8(msgType.code)
        // [1] groupId
        out[1] = u8(groupId)
        // [2..4] fgColor RGB
        out[2] = u8(color.r); out[3] = u8(color.g); out[4] = u8(color.b)
        // [5..7] bgColor RGB
        out[5] = u8(backgroundColor.r); out[6] = u8(backgroundColor.g); out[7] = u8(backgroundColor.b)
        // [8] effectType code
        out[8] = u8(effectType.code)
        // [9..10] durationMs
        u16le(durationMs).copyInto(out, 9)
        // [11..17] tail fields
        out[11] = u8(period)
        out[12] = u8(spf)
        out[13] = u8(randomColor)
        out[14] = u8(randomDelay)
        out[15] = u8(fade)
        out[16] = u8(broadcasting)
        out[17] = 0 // reserved
        // [18..19] effectIndex
        u16le(effectIndex).copyInto(out, 18)
        return out
    }

    /**
     * Convenience factories for common effects.
     * All fields can be customized; those not provided use sensible defaults.
     *
     * Note: Primary parameters (color, period, etc.) are listed first for ease of use,
     * while optional parameters (msgType, groupId, spf, fade, etc.) follow with defaults.
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
         * @param msgType Message type (default: [MsgType.MUSIC]).
         * @param groupId Target group: 0=all, 1..20=one group (default: 0).
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
            msgType: MsgType = MsgType.MUSIC,
            groupId: Int = 0,
            spf: Int = 100,
            fade: Int = 100,
            broadcasting: Int = 0,
            effectIndex: Int = 0
        ) = LSEffectPayload(
            msgType = msgType,
            groupId = groupId,
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
         * @param msgType Message type (default: [MsgType.MUSIC]).
         * @param groupId Target group: 0=all, 1..20=one group (default: 0).
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
            msgType: MsgType = MsgType.MUSIC,
            groupId: Int = 0,
            spf: Int = 100,
            fade: Int = 100,
            broadcasting: Int = 0,
            effectIndex: Int = 0,
        ) = LSEffectPayload(
            msgType = msgType,
            groupId = groupId,
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
         * @param msgType Message type (default: [MsgType.MUSIC]).
         * @param groupId Target group: 0=all, 1..20=one group (default: 0).
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
            msgType: MsgType = MsgType.MUSIC,
            broadcasting: Int = 0,
            spf: Int = 100,
            fade: Int = 100,
            effectIndex: Int = 0,
            groupId: Int = 0
        ) = LSEffectPayload(
            msgType = msgType,
            groupId = groupId,
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
         * @param msgType Message type (default: [MsgType.MUSIC]).
         * @param groupId Target group: 0=all, 1..20=one group (default: 0).
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
            msgType: MsgType = MsgType.MUSIC,
            broadcasting: Int = 0,
            spf: Int = 100,
            fade: Int = 100,
            effectIndex: Int = 0,
            groupId: Int = 0
        ) = LSEffectPayload(
            msgType = msgType,
            groupId = groupId,
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
         * @param msgType Message type (default: [MsgType.MUSIC]).
         * @param groupId Target group: 0=all, 1..20=one group (default: 0).
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
            msgType: MsgType = MsgType.MUSIC,
            broadcasting: Int = 0,
            spf: Int = 100,
            fade: Int = 100,
            effectIndex: Int = 0,
            groupId: Int = 0
        ) = LSEffectPayload(
            msgType = msgType,
            groupId = groupId,
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

    /**
     * Factories for the Glowsync group mapping protocol (v3): GroupSetup (msgType=2) and
     * GroupControl (msgType=3) messages, built on the same 20-byte [LSEffectPayload] frame.
     *
     * These reuse [randomColor]=0, [randomDelay]=1, [fade]=0 and [broadcasting]=0 — fixed
     * values the group feature doesn't use.
     */
    object Group {

        /**
         * Default (period, spf) per [EffectType], reverse-engineered from the firmware's
         * timing formula:
         * ```
         * BLINK / STROBE segment (on or off) ms   = (spf / 2) * period
         * BREATH segment (fade-up/hold/down/hold) = (spf / 2) * (period / 2)
         * ```
         * These are estimates; adjust here if real-device timing feels off — no firmware
         * rebuild required, since period/spf are just message field values.
         */
        @JvmStatic
        fun defaultPeriodSpf(effectType: EffectType): Pair<Int, Int> = when (effectType) {
            EffectType.OFF, EffectType.ON -> 0 to 100
            EffectType.STROBE -> 10 to 20
            EffectType.BLINK -> 6 to 100
            EffectType.BREATH -> 20 to 100
        }

        /**
         * Builds a **GroupSetup** (msgType=2) broadcast: the "join group [groupId]" beacon
         * sent while the organizer holds the group screen open.
         *
         * Unassigned lightsticks blink [GroupPalette.colorFor] while this is being broadcast;
         * pressing the lightstick's button locks it to this group. There is no explicit
         * "stop" message — the caller decides when to move on to the next group's setup.
         *
         * @param groupId Group to advertise (1..20).
         * @throws IllegalArgumentException If [groupId] is outside 1..20.
         */
        @JvmStatic
        fun setup(groupId: Int): LSEffectPayload {
            require(groupId in GroupPalette.MIN_GROUP_ID..GroupPalette.MAX_GROUP_ID) {
                "groupId must be within ${GroupPalette.MIN_GROUP_ID}..${GroupPalette.MAX_GROUP_ID} for GroupSetup"
            }
            return LSEffectPayload(
                msgType = MsgType.GROUP_SETUP,
                groupId = groupId,
                color = GroupPalette.colorFor(groupId),
                backgroundColor = Colors.BLACK,
                effectType = EffectType.BLINK,
                period = 6,
                spf = 100,
                randomColor = 0,
                randomDelay = 1,
                fade = 0,
                broadcasting = 0
            )
        }

        /**
         * Builds a **GroupControl** (msgType=3) command that plays [effectType] on the
         * targeted group.
         *
         * @param groupId Target group: 0 = all groups, 1..20 = a single group.
         * @param effectType Effect to play (OFF/ON/STROBE/BLINK/BREATH).
         * @param color Foreground color. Defaults to [GroupPalette.colorFor] for a single
         *        group (so control re-plays that group's own color), or white for the
         *        all-groups broadcast (groupId=0).
         * @param backgroundColor Background color for BLINK/BREATH (default: black).
         * @param durationMs Duration in ms (default: 0). Not yet auto-reverted by firmware.
         * @param period Optional timing override; defaults per [defaultPeriodSpf].
         * @param spf Optional timing override; defaults per [defaultPeriodSpf].
         * @throws IllegalArgumentException If [groupId] is outside 0..20.
         */
        @JvmStatic
        @JvmOverloads
        fun control(
            groupId: Int,
            effectType: EffectType,
            color: Color? = null,
            backgroundColor: Color = Colors.BLACK,
            durationMs: Int = 0,
            period: Int? = null,
            spf: Int? = null
        ): LSEffectPayload {
            require(groupId in 0..20) { "groupId must be within 0..20 for GroupControl" }
            val (defaultPeriod, defaultSpf) = defaultPeriodSpf(effectType)
            val fg = color ?: if (groupId in GroupPalette.MIN_GROUP_ID..GroupPalette.MAX_GROUP_ID) {
                GroupPalette.colorFor(groupId)
            } else {
                Colors.WHITE
            }
            return LSEffectPayload(
                msgType = MsgType.GROUP_CONTROL,
                groupId = groupId,
                color = fg,
                backgroundColor = backgroundColor,
                effectType = effectType,
                durationMs = durationMs,
                period = period ?: defaultPeriod,
                spf = spf ?: defaultSpf,
                randomColor = 0,
                randomDelay = 1,
                fade = 0,
                broadcasting = 0
            )
        }
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

            val msgType = MsgType.fromCode(u8())
            require(msgType != MsgType.GAME_MODE) {
                "bytes is a Game Mode payload (msgType=GAME_MODE), not an LSEffectPayload frame"
            }
            val groupId = u8()
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
            u8() // reserved (ignored)
            val effectIndex = u16()

            return LSEffectPayload(
                msgType = msgType,
                groupId = groupId,
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
                effectIndex = effectIndex
            )
        }
    }
}
