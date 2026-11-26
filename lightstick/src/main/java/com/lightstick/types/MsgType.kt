package com.lightstick.types

/**
 * Message type carried in byte offset 0 of every 20-byte frame (protocol v2.0, extended in v2.2;
 * "msgType 단일 판별자 + groupMask" — supersedes the earlier `GROUP_CONTROL` msgType).
 *
 * This is the **single, sole discriminator** for the whole frame — every message (single/group
 * effect control, GameMode, GroupSetup) shares one 20-byte layout that starts with this byte
 * and carries an 8-bit `effectIndex` at offset 17 (pure dedup/sequence number, uninvolved
 * in message-type discrimination; protocol v2.1). Layout A also ends with a `duration` u16.
 * Layout C ([CONTROL], v2.2) is used on FF0A only.
 *
 * There is no longer a separate "GroupControl" msgType: targeting one group, an arbitrary
 * combination of groups, or every connected lightstick is now expressed by [EFFECT] plus the
 * `groupMask` field (offset 1-4) — see [Group] for the mask constants.
 *
 * `GAME_MODE` messages (Game Mode 1-4) use a different field layout for offsets 1-17 than
 * [EFFECT]/[GROUP_SETUP] do — see the shared protocol doc's "레이아웃 B" — so [LSEffectPayload]
 * (which implements layout A) rejects frames carrying this value; build GameMode payloads
 * through the FF03 game command path instead.
 *
 * @property code The numeric wire value written at payload offset 0.
 * @since 2.0.0 (CONTROL added in 2.200.0)
 */
enum class MsgType(val code: Int) {
    /**
     * Single or group-targeted effect payload (timeline / one-off effect sends, including
     * group control via `groupMask`). Not limited to music-synced playback — covers what used
     * to be split across `MUSIC` and the now-removed `GROUP_CONTROL`.
     */
    EFFECT(0),

    /** Game Mode 1-4 command/result — uses a different field layout (see class doc). */
    GAME_MODE(1),

    /** Group join broadcast — see `Device.sendGroupSetting`. */
    GROUP_SETUP(2),

    /**
     * Show control (protocol v2.2, layout C) — FF0A only, never on FF02. Build these through
     * `Device.sendControl`; [LSEffectPayload] (layout A) rejects frames carrying this value.
     */
    CONTROL(3);

    companion object {
        /**
         * Resolves a [MsgType] by its wire [code].
         *
         * Unknown codes are **not** mapped to [EFFECT]: per protocol v2.2 a wand ignores a frame
         * whose msgType it does not know, so callers must ignore it too.
         *
         * @return The corresponding [MsgType], or `null` if [code] is unknown (ignore the frame).
         */
        @JvmStatic
        fun fromCode(code: Int): MsgType? =
            entries.firstOrNull { it.code == code }
    }
}
