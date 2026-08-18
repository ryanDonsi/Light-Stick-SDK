package com.lightstick.group

import com.lightstick.types.Color
import com.lightstick.types.Colors
import com.lightstick.types.EffectType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Group protocol payload (Glowsync group mapping spec v2.2) that encodes to an exact
 * **20-byte** frame — the same struct written to FF02 and passed through by the relay
 * to the 802.15.4 broadcast without any field parsing.
 *
 * Byte layout (indices in brackets, little-endian for u16):
 *  [0..1]   effectIndex    (u16)  – Unused by the group feature, always 0.
 *  [2]      msgType        (u8)   – See [GroupMsgType].
 *  [3]      groupId        (u8)   – 0 = unassigned/all, 1..20 = group.
 *  [4..6]   fgColor RGB    (3xu8) – Foreground color.
 *  [7..9]   bgColor RGB    (3xu8) – Background color (BLINK/BREATH "off" color).
 *  [10]     effectType     (u8)   – enum code, see [EffectType.code].
 *  [11..12] durationMs     (u16)  – Not yet auto-reverted by firmware (spec §6).
 *  [13]     period         (u8)   – See timing formula on [defaultPeriodSpf].
 *  [14]     spf            (u8)   – See timing formula on [defaultPeriodSpf].
 *  [15]     randomColor    (u8)   – Unused by the group feature, always 0.
 *  [16]     randomDelay    (u8)   – Unused by the group feature, always 1.
 *  [17]     fadeValue      (u8)   – Unused by the group feature, always 0.
 *  [18]     broadcasting   (u8)   – Unrelated to groups, always 0.
 *  [19]     syncIndex      (u8)   – Legacy field, left untouched (default 0).
 *
 * Prefer the [setup] and [control] factories over the raw constructor — they fill in
 * the group-feature-specific fixed fields and apply the documented defaults.
 *
 * @throws IllegalArgumentException If any field is outside its valid range.
 * @since 1.5.0
 *
 * @sample com.lightstick.samples.GroupSamples.sampleGroupSetup
 * @sample com.lightstick.samples.GroupSamples.sampleGroupControl
 */
data class GroupPayload(
    val msgType: GroupMsgType,
    val groupId: Int,
    val fgColor: Color,
    val bgColor: Color = Colors.BLACK,
    val effectType: EffectType,
    val durationMs: Int = 0,
    val period: Int,
    val spf: Int,
    val effectIndex: Int = 0,
    val syncIndex: Int = 0
) {

    init {
        require(effectIndex in 0..0xFFFF) { "effectIndex must be within 0..65535" }
        require(groupId in 0..20) { "groupId must be within 0..20" }
        require(durationMs in 0..0xFFFF) { "durationMs must be within 0..65535" }
        require(period in 0..255) { "period must be within 0..255" }
        require(spf in 0..255) { "spf must be within 0..255" }
        require(syncIndex in 0..255) { "syncIndex must be within 0..255" }
    }

    /**
     * Encodes this payload to a **20-byte** frame, identical in shape to [com.lightstick.types.LSEffectPayload]
     * but with offsets 0–3 reinterpreted as effectIndex/msgType/groupId per the group protocol.
     *
     * @return A [ByteArray] of length 20 representing this payload.
     */
    fun toByteArray(): ByteArray {
        fun u8(v: Int) = (v and 0xFF).toByte()
        fun u16le(v: Int) = byteArrayOf(u8(v), u8(v ushr 8))

        val out = ByteArray(20)
        u16le(effectIndex).copyInto(out, 0)
        out[2] = u8(msgType.code)
        out[3] = u8(groupId)
        out[4] = u8(fgColor.r); out[5] = u8(fgColor.g); out[6] = u8(fgColor.b)
        out[7] = u8(bgColor.r); out[8] = u8(bgColor.g); out[9] = u8(bgColor.b)
        out[10] = u8(effectType.code)
        u16le(durationMs).copyInto(out, 11)
        out[13] = u8(period)
        out[14] = u8(spf)
        out[15] = u8(RANDOM_COLOR_FIXED)
        out[16] = u8(RANDOM_DELAY_FIXED)
        out[17] = u8(FADE_VALUE_FIXED)
        out[18] = u8(BROADCASTING_FIXED)
        out[19] = u8(syncIndex)
        return out
    }

    companion object {
        private const val RANDOM_COLOR_FIXED = 0
        private const val RANDOM_DELAY_FIXED = 1
        private const val FADE_VALUE_FIXED = 0
        private const val BROADCASTING_FIXED = 0

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
        fun setup(groupId: Int): GroupPayload {
            require(groupId in GroupPalette.MIN_GROUP_ID..GroupPalette.MAX_GROUP_ID) {
                "groupId must be within ${GroupPalette.MIN_GROUP_ID}..${GroupPalette.MAX_GROUP_ID} for GroupSetup"
            }
            return GroupPayload(
                msgType = GroupMsgType.GROUP_SETUP,
                groupId = groupId,
                fgColor = GroupPalette.colorFor(groupId),
                bgColor = Colors.BLACK,
                effectType = EffectType.BLINK,
                period = 6,
                spf = 100
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
        ): GroupPayload {
            require(groupId in 0..20) { "groupId must be within 0..20 for GroupControl" }
            val (defaultPeriod, defaultSpf) = defaultPeriodSpf(effectType)
            val fg = color ?: if (groupId in GroupPalette.MIN_GROUP_ID..GroupPalette.MAX_GROUP_ID) {
                GroupPalette.colorFor(groupId)
            } else {
                Colors.WHITE
            }
            return GroupPayload(
                msgType = GroupMsgType.GROUP_CONTROL,
                groupId = groupId,
                fgColor = fg,
                bgColor = backgroundColor,
                effectType = effectType,
                durationMs = durationMs,
                period = period ?: defaultPeriod,
                spf = spf ?: defaultSpf
            )
        }

        /**
         * Reconstructs a payload from a **20-byte** serialized frame. Mirrors [toByteArray]
         * layout exactly; fixed fields (randomColor/randomDelay/fadeValue/broadcasting) are
         * read but not exposed, since the group feature always writes their documented
         * constant values.
         *
         * @throws IllegalArgumentException If [bytes] length is not exactly 20.
         */
        @JvmStatic
        fun fromByteArray(bytes: ByteArray): GroupPayload {
            require(bytes.size == 20) { "GroupPayload must be 20 bytes (got ${bytes.size})" }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            fun u16(): Int = bb.short.toInt() and 0xFFFF
            fun u8(): Int = bb.get().toInt() and 0xFF

            val effectIndex = u16()
            val msgType = GroupMsgType.fromCode(u8())
            val groupId = u8()
            val fgR = u8(); val fgG = u8(); val fgB = u8()
            val bgR = u8(); val bgG = u8(); val bgB = u8()
            val effectType = EffectType.fromCode(u8())
            val durationMs = u16()
            val period = u8()
            val spf = u8()
            u8() // randomColor (fixed, ignored)
            u8() // randomDelay (fixed, ignored)
            u8() // fadeValue (fixed, ignored)
            u8() // broadcasting (fixed, ignored)
            val syncIndex = u8()

            return GroupPayload(
                msgType = msgType,
                groupId = groupId,
                fgColor = Color(fgR, fgG, fgB),
                bgColor = Color(bgR, bgG, bgB),
                effectType = effectType,
                durationMs = durationMs,
                period = period,
                spf = spf,
                effectIndex = effectIndex,
                syncIndex = syncIndex
            )
        }
    }
}
