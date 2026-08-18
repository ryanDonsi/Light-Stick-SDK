package com.lightstick.types

/**
 * Message type carried in byte offset 2 of the 20-byte [LSEffectPayload] frame (protocol v2.2).
 *
 * As of v2.2 this byte — together with `groupId` at offset 3 — replaces the old
 * offset 2-3 `ledMask` (LED bitmask) field, which firmware no longer reads. The
 * relay passes both bytes through untouched (pure 802.15.4 broadcast, no field
 * parsing) — only the lightstick firmware interprets them.
 *
 * @property code The numeric wire value written at payload offset 2.
 * @since 1.5.0
 */
enum class MsgType(val code: Int) {
    /** Music-synchronized effect payload (timeline / one-off effect sends). */
    MUSIC(0),

    /** Game-mode payload. */
    GAME(1),

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
