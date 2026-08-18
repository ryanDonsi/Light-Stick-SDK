package com.lightstick.types

/**
 * Message type carried in byte offset 0 of the 20-byte [LSEffectPayload] frame (protocol v2.0,
 * "msgType 단일 판별자 + groupMask" — supersedes the earlier `GROUP_CONTROL` msgType).
 *
 * This is the **single, sole discriminator** for the whole frame — every message (single/group
 * effect control, GameMode, GroupSetup) shares one 20-byte layout that starts with this byte
 * and ends with a 16-bit `effectIndex` (offset 18-19, pure dedup/sequence number, uninvolved
 * in message-type discrimination).
 *
 * There is no longer a separate "GroupControl" msgType: targeting one group, an arbitrary
 * combination of groups, or every connected lightstick is now expressed by [EFFECT] plus the
 * `groupMask` field (offset 1-4) — see [LSEffectPayload.Group] for the mask constants.
 *
 * `GAME_MODE` messages (Game Mode 1-4) use a different field layout for offsets 1-17 than
 * [EFFECT]/[GROUP_SETUP] do — see the shared protocol doc's "레이아웃 B" — so [LSEffectPayload]
 * (which implements layout A) rejects frames carrying this value; build GameMode payloads
 * through the FF03 game command path instead.
 *
 * @property code The numeric wire value written at payload offset 0.
 * @since 1.5.0
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
    GROUP_SETUP(2);

    companion object {
        /**
         * Resolves a [MsgType] by its wire [code].
         *
         * @return The corresponding [MsgType], or [EFFECT] if unknown.
         */
        @JvmStatic
        fun fromCode(code: Int): MsgType =
            entries.firstOrNull { it.code == code } ?: EFFECT
    }
}
