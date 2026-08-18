package com.lightstick.types

import com.lightstick.group.GroupPalette
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Structured LightStick Effect payload (protocol v2.2, `LE_BLE_PATLOAD_T`) that encodes to an
 * exact **20-byte** frame written to FF02.
 *
 * Byte layout (indices in brackets, little-endian for u16):
 *  [0..1]   effectIndex    (u16)  – Fixed `0x0000`. See "Payload discrimination" below.
 *  [2]      msgType        (u8)   – See [MsgType]. Default [MsgType.MUSIC].
 *  [3]      groupId        (u8)   – 0 = unassigned/all, 1..20 = group (see [com.lightstick.group.GroupPalette]).
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
 * As of protocol v2.2, offsets 0-3 carry effectIndex/msgType/groupId, replacing the v1.4
 * `mode`(u16)/`ledMask`(u16) fields — firmware no longer reads `ledMask`; per-LED targeting
 * is not part of the current protocol. Offsets 4-19 are unchanged from v1.4.
 *
 * ### Payload discrimination (shared with the Game Mode protocol)
 *
 * The relay/lightstick firmware share one physical 20-byte buffer between two unrelated
 * features and reinterpret it in two steps, `effectIndex` first:
 * - `effectIndex == 0x0005` → a Game Mode 1-4 command (`LE_GAME_PATLOAD_T`, offsets 2-19 mean
 *   something completely different — subIndex/cmdIndex/level/option/etc.). Sent to **FF03**;
 *   see `GameManager.EFFECT_INDEX_GAME`. Never construct this via [LSEffectPayload].
 * - `effectIndex == 0x0000` → this class (`LE_BLE_PATLOAD_T`), sent to **FF02**, further split
 *   by [msgType] into Music/Game/GroupSetup/GroupControl.
 *
 * Because of this, `effectIndex` is **not** a caller-configurable field: [toByteArray] always
 * writes `0x0000`, and [fromByteArray] throws if the source bytes carry `0x0005` — those bytes
 * are a Game Mode command, not a valid [LSEffectPayload] frame, and decoding them here would
 * silently misread every other field.
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
 * @param syncIndex Unsigned byte (0–255), used for device sync/slot, default: 0.
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
    val syncIndex: Int = 0
) {

    init {
        require(groupId in 0..20)         { "groupId must be within 0..20" }
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
        // [0..1] effectIndex — fixed 0x0000 (0x0005 marks a Game Mode FF03 payload instead)
        u16le(EFFECT_INDEX_LED_GROUP).copyInto(out, 0)
        // [2] msgType
        out[2] = u8(msgType.code)
        // [3] groupId
        out[3] = u8(groupId)
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
            msgType: MsgType = MsgType.MUSIC,
            groupId: Int = 0,
            spf: Int = 100,
            fade: Int = 100,
            broadcasting: Int = 0,
            syncIndex: Int = 0
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
         * @param msgType Message type (default: [MsgType.MUSIC]).
         * @param groupId Target group: 0=all, 1..20=one group (default: 0).
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
            msgType: MsgType = MsgType.MUSIC,
            groupId: Int = 0,
            spf: Int = 100,
            fade: Int = 100,
            broadcasting: Int = 0,
            syncIndex: Int = 0,
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
         * @param msgType Message type (default: [MsgType.MUSIC]).
         * @param groupId Target group: 0=all, 1..20=one group (default: 0).
         * @param broadcasting Broadcasting flag (0=single device, 1=broadcast, default: 0).
         * @param spf Samples per frame (default: 100).
         * @param fade Fade parameter (default: 100).
         * @param syncIndex Sync index (default: 0).
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
            syncIndex: Int = 0,
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
            syncIndex = syncIndex
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
         * @param syncIndex Sync index (default: 0).
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
            syncIndex: Int = 0,
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
            syncIndex = syncIndex
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
         * @param syncIndex Sync index (default: 0).
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
            syncIndex: Int = 0,
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
            syncIndex = syncIndex
        )
    }

    /**
     * Factories for the Glowsync group mapping protocol (v2.2): GroupSetup (msgType=2) and
     * GroupControl (msgType=3) messages, built on the same 20-byte [LSEffectPayload] frame.
     *
     * These reuse [randomColor]=0, [randomDelay]=1, [fade]=0 and [broadcasting]=0 — fixed
     * values the group feature doesn't use — and leave [syncIndex] at the legacy default (0).
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

        /** Fixed `effectIndex` value marking this 20-byte frame as `LE_BLE_PATLOAD_T` (FF02). */
        private const val EFFECT_INDEX_LED_GROUP = 0x0000

        /** `effectIndex` value that instead marks a Game Mode 1-4 command (`LE_GAME_PATLOAD_T`, FF03). */
        private const val EFFECT_INDEX_GAME = 0x0005

        /**
         * Reconstructs a payload from a **20-byte** serialized frame.
         * Mirrors [toByteArray] layout exactly.
         *
         * @param bytes A 20-byte array containing the serialized payload.
         * @return A deserialized [LSEffectPayload] instance.
         * @throws IllegalArgumentException If [bytes] length is not exactly 20, or if byte[0-1]
         *         (effectIndex) isn't `0x0000` — `0x0005` means [bytes] is a Game Mode FF03
         *         command (`LE_GAME_PATLOAD_T`), not a valid [LSEffectPayload] frame; decoding
         *         it here would silently misread every other field.
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
            require(effectIndex == EFFECT_INDEX_LED_GROUP) {
                if (effectIndex == EFFECT_INDEX_GAME) {
                    "bytes is a Game Mode FF03 command (effectIndex=0x0005), not an LSEffectPayload frame"
                } else {
                    "effectIndex must be 0x0000 for LSEffectPayload (got 0x%04X)".format(effectIndex)
                }
            }
            val msgType = MsgType.fromCode(u8())
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
            val syncIndex = u8()

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
                syncIndex = syncIndex
            )
        }
    }
}
