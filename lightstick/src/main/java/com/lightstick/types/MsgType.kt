package com.lightstick.types

/**
 * Message type carried in byte offset 0 of the 20-byte [LSEffectPayload] frame (protocol v3).
 *
 * As of v3 this is the **single, sole discriminator** for the whole frame — every message
 * (Music, GameMode, GroupSetup, GroupControl) shares one 20-byte layout that starts with
 * this byte and ends with a 16-bit `effectIndex` (offset 18-19, pure dedup/sequence number,
 * uninvolved in message-type discrimination). This replaces the v2 scheme where `effectIndex`
 * at offset 0-1 did double duty as a first-level game/non-game discriminator ahead of this
 * byte at offset 2.
 *
 * `GAME_MODE` messages (Game Mode 1-4) use a different field layout for offsets 1-17 than
 * Music/GroupSetup/GroupControl do — see the shared protocol doc's "body 레이아웃 B" — so
 * [LSEffectPayload] (which implements layout A) rejects frames carrying this value; build
 * GameMode payloads through the FF03 game command path instead.
 *
 * @property code The numeric wire value written at payload offset 0.
 * @since 1.5.0
 */
enum class MsgType(val code: Int) {
    /** Music-synchronized effect payload (timeline / one-off effect sends). */
    MUSIC(0),

    /** Game Mode 1-4 command/result — uses a different field layout (see class doc). */
    GAME_MODE(1),

    /** Group join broadcast — see [LSEffectPayload.Group.setup]. */
    GROUP_SETUP(2),

    /** Group effect control — see [LSEffectPayload.Group.control]. */
    GROUP_CONTROL(3);

    companion object {
        /**
         * Resolves a [MsgType] by its wire [code].
         *
         * @return The corresponding [MsgType], or [MUSIC] if unknown.
         */
        @JvmStatic
        fun fromCode(code: Int): MsgType =
            entries.firstOrNull { it.code == code } ?: MUSIC
    }
}
