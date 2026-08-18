package com.lightstick.types

import com.lightstick.group.GroupPalette
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Structured LightStick Effect payload (protocol v2.0, `LE_LED_PATLOAD_T`) that encodes to an
 * exact **20-byte** frame written to FF02.
 *
 * Byte layout (indices in brackets, little-endian for multi-byte fields). Shared by
 * [MsgType.MUSIC] and [MsgType.GROUP_SETUP] — [MsgType.GAME_MODE] uses a different layout
 * entirely (see [MsgType]) and is rejected by [fromByteArray]:
 *  [0]      msgType        (u8)   – Sole message-type discriminator. Default [MsgType.MUSIC].
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
 * As of this protocol revision, `groupMask` (a 32-bit bitmask) replaces the earlier single-byte
 * `groupId` — and with it, the dedicated `GROUP_CONTROL` msgType is gone: group targeting is now
 * expressed by [MsgType.MUSIC] plus a non-zero mask. The 3 extra bytes this costs come out of
 * `durationMs`, which is removed (firmware never consumed it) and the old reserved byte.
 *
 * Validation:
 * - Throws [IllegalArgumentException] if any field is outside its valid range.
 *
 * @param msgType Message type (u8); see [MsgType]. Default: [MsgType.MUSIC].
 * @param groupMask 32-bit group bitmask (0..0xFFFFFFFF); see [Group] for building one from
 *        group IDs. Default: 0 (single/all, ignoring group membership).
 * @param color RGB foreground color for this effect, default: WHITE.
 * @param backgroundColor RGB background color for this effect, default: BLACK.
 * @param effectType Logical effect type; serialized as [EffectType.code], default: ON.
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
    val groupMask: Long = 0L,
    val color: Color = Colors.WHITE,
    val backgroundColor: Color = Colors.BLACK,
    val effectType: EffectType = EffectType.ON,
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
         * @param msgType Message type (default: [MsgType.MUSIC]).
         * @param groupMask Group bitmask; 0=single/all (default). See [Group].
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
            groupMask: Long = 0L,
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
         * @param msgType Message type (default: [MsgType.MUSIC]).
         * @param groupMask Group bitmask; 0=single/all (default). See [Group].
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
            groupMask: Long = 0L,
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
         * @param msgType Message type (default: [MsgType.MUSIC]).
         * @param groupMask Group bitmask; 0=single/all (default). See [Group].
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
            groupMask: Long = 0L
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
         * @param msgType Message type (default: [MsgType.MUSIC]).
         * @param groupMask Group bitmask; 0=single/all (default). See [Group].
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
            groupMask: Long = 0L
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
         * @param msgType Message type (default: [MsgType.MUSIC]).
         * @param groupMask Group bitmask; 0=single/all (default). See [Group].
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
            groupMask: Long = 0L
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

    /**
     * Factories for the Glowsync group mapping protocol: GroupSetup (msgType=2) and
     * group-targeted control (msgType=0/MUSIC + `groupMask`), built on the same 20-byte
     * [LSEffectPayload] frame.
     *
     * There is no separate "GroupControl" msgType — targeting is expressed entirely by
     * `groupMask` (offset 1-4): a 32-bit bitmask where bit(N-1) selects group N (1..32),
     * any combination of bits can be set at once (e.g. group 1 + group 3 in a single frame,
     * so every targeted lightstick reacts to the exact same 802.15.4 packet simultaneously —
     * unlike a sequential "wave" of separate messages), `0` means single/all (ignore group
     * membership entirely), and `0xFFFFFFFF` means every group.
     *
     * These reuse [randomColor]=0, [randomDelay]=1, [fade]=0 and [broadcasting]=0 — fixed
     * values the group feature doesn't use.
     */
    object Group {

        /** Mask value meaning "single/all — ignore group membership entirely" (spec: `0`). */
        const val MASK_ALL_SINGLE: Long = 0L

        /** Mask value meaning "every group" — all 32 bits set (spec: `0xFFFFFFFF`). */
        const val MASK_ALL_GROUPS: Long = 0xFFFFFFFFL

        /** Minimum valid group id for mask bit positions. */
        const val MIN_GROUP_ID: Int = 1

        /** Maximum valid group id for mask bit positions (32-bit mask). */
        const val MAX_GROUP_ID: Int = 32

        /**
         * Builds a `groupMask` selecting a single group.
         *
         * @param groupId Group id (1..32).
         * @throws IllegalArgumentException If [groupId] is outside 1..32.
         */
        @JvmStatic
        fun maskFor(groupId: Int): Long {
            require(groupId in MIN_GROUP_ID..MAX_GROUP_ID) {
                "groupId must be within $MIN_GROUP_ID..$MAX_GROUP_ID (got $groupId)"
            }
            return 1L shl (groupId - 1)
        }

        /**
         * Builds a `groupMask` selecting an arbitrary combination of groups (e.g. group 1 +
         * group 3), OR-ing each id's bit together.
         *
         * @param groupIds Group ids to combine (each 1..32).
         * @throws IllegalArgumentException If [groupIds] is empty or any id is outside 1..32.
         */
        @JvmStatic
        fun maskFor(groupIds: Collection<Int>): Long {
            require(groupIds.isNotEmpty()) { "groupIds must not be empty" }
            return groupIds.fold(0L) { acc, id -> acc or maskFor(id) }
        }

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
         * @param groupId Group to advertise (1..20 — see [GroupPalette] for the palette's
         *        current range; groups 21..32 are addressable on the wire but have no built-in
         *        palette color yet).
         * @throws IllegalArgumentException If [groupId] is outside 1..20.
         */
        @JvmStatic
        fun setup(groupId: Int): LSEffectPayload {
            require(groupId in GroupPalette.MIN_GROUP_ID..GroupPalette.MAX_GROUP_ID) {
                "groupId must be within ${GroupPalette.MIN_GROUP_ID}..${GroupPalette.MAX_GROUP_ID} for GroupSetup"
            }
            return LSEffectPayload(
                msgType = MsgType.GROUP_SETUP,
                groupMask = maskFor(groupId),
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
         * Builds a group-targeted control command (msgType=[MsgType.MUSIC] + `groupMask`) that
         * plays [effectType] on every group selected by [groupMask].
         *
         * Every group whose bit is set reacts to the same 802.15.4 packet at once — this is
         * how simultaneous multi-group control (e.g. group 1 + group 3 together) differs from
         * a sequential "wave" of separate single-group messages.
         *
         * This is the low-level primitive for callers who already have a raw mask (e.g. their
         * own group-membership bitset). For a single group or a list of ids, prefer [control]
         * or [controlGroups] — deliberately **not** overloads of this function: a bare `Long`
         * mask and an `Int` group id look alike at a call site but mean very different things
         * (`5` = group 5, `5L` = mask `0b101` = groups 1+3), so they get distinct names instead
         * of relying on the reader to notice an `L` suffix.
         *
         * @param groupMask Target groups; see [maskFor], [MASK_ALL_SINGLE], [MASK_ALL_GROUPS].
         * @param effectType Effect to play (OFF/ON/STROBE/BLINK/BREATH).
         * @param color Foreground color (required — with an arbitrary/combined mask there's no
         *        single sensible palette default).
         * @param backgroundColor Background color for BLINK/BREATH (default: black).
         * @param period Optional timing override; defaults per [defaultPeriodSpf].
         * @param spf Optional timing override; defaults per [defaultPeriodSpf].
         */
        @JvmStatic
        @JvmOverloads
        fun controlMask(
            groupMask: Long,
            effectType: EffectType,
            color: Color,
            backgroundColor: Color = Colors.BLACK,
            period: Int? = null,
            spf: Int? = null
        ): LSEffectPayload {
            val (defaultPeriod, defaultSpf) = defaultPeriodSpf(effectType)
            return LSEffectPayload(
                msgType = MsgType.MUSIC,
                groupMask = groupMask,
                color = color,
                backgroundColor = backgroundColor,
                effectType = effectType,
                period = period ?: defaultPeriod,
                spf = spf ?: defaultSpf,
                randomColor = 0,
                randomDelay = 1,
                fade = 0,
                broadcasting = 0
            )
        }

        /**
         * Builds a control command targeting a single group.
         *
         * @param groupId Target group (1..32).
         * @param color Foreground color. Defaults to [GroupPalette.colorFor] for groups 1..20;
         *        required (throws if omitted) for groups 21..32, which have no palette entry.
         * @throws IllegalArgumentException If [groupId] is outside 1..32, or if [color] is
         *         omitted for a [groupId] outside the palette's 1..20 range.
         */
        @JvmStatic
        @JvmOverloads
        fun control(
            groupId: Int,
            effectType: EffectType,
            color: Color? = null,
            backgroundColor: Color = Colors.BLACK,
            period: Int? = null,
            spf: Int? = null
        ): LSEffectPayload {
            val fg = color ?: if (groupId in GroupPalette.MIN_GROUP_ID..GroupPalette.MAX_GROUP_ID) {
                GroupPalette.colorFor(groupId)
            } else {
                throw IllegalArgumentException(
                    "color is required for groupId=$groupId (no palette entry outside " +
                        "${GroupPalette.MIN_GROUP_ID}..${GroupPalette.MAX_GROUP_ID})"
                )
            }
            return controlMask(maskFor(groupId), effectType, fg, backgroundColor, period, spf)
        }

        /**
         * Builds a control command targeting an arbitrary combination of groups at once (e.g.
         * `controlGroups(setOf(1, 3), ...)`), OR-ing their bits into one `groupMask` so they
         * all react to the same packet simultaneously.
         *
         * @param groupIds Groups to target together (each 1..32, non-empty).
         * @param color Foreground color (required — no single palette color applies to a
         *        combination of groups).
         */
        @JvmStatic
        @JvmOverloads
        fun controlGroups(
            groupIds: Collection<Int>,
            effectType: EffectType,
            color: Color,
            backgroundColor: Color = Colors.BLACK,
            period: Int? = null,
            spf: Int? = null
        ): LSEffectPayload = controlMask(maskFor(groupIds), effectType, color, backgroundColor, period, spf)

        /**
         * Builds a control command for [MASK_ALL_SINGLE] — every connected lightstick reacts
         * regardless of group assignment (including lightsticks never assigned a group).
         */
        @JvmStatic
        @JvmOverloads
        fun controlAllSingle(
            effectType: EffectType,
            color: Color = Colors.WHITE,
            backgroundColor: Color = Colors.BLACK,
            period: Int? = null,
            spf: Int? = null
        ): LSEffectPayload = controlMask(MASK_ALL_SINGLE, effectType, color, backgroundColor, period, spf)

        /**
         * Builds a control command for [MASK_ALL_GROUPS] — every *group-assigned* lightstick
         * reacts (a lightstick never assigned to any group won't match any bit, so it does
         * **not** react — unlike [controlAllSingle]).
         */
        @JvmStatic
        @JvmOverloads
        fun controlAllGroups(
            effectType: EffectType,
            color: Color = Colors.WHITE,
            backgroundColor: Color = Colors.BLACK,
            period: Int? = null,
            spf: Int? = null
        ): LSEffectPayload = controlMask(MASK_ALL_GROUPS, effectType, color, backgroundColor, period, spf)
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
