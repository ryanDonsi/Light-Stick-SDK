package com.lightstick.group

/**
 * Message type carried in byte offset 2 of the 20-byte Group protocol payload
 * (Glowsync group mapping spec v2.2).
 *
 * The relay passes this byte through untouched (pure 802.15.4 broadcast, no field
 * parsing) — only the lightstick firmware interprets it.
 *
 * @property code The numeric wire value written at payload offset 2.
 * @since 1.5.0
 */
enum class GroupMsgType(val code: Int) {
    /** Music-synchronized effect payload (unrelated to group assignment). */
    MUSIC(0),

    /** Game-mode payload (unrelated to group assignment). */
    GAME(1),

    /** Group join broadcast — see [GroupPayload.setup]. */
    GROUP_SETUP(2),

    /** Group effect control — see [GroupPayload.control]. */
    GROUP_CONTROL(3);

    companion object {
        /**
         * Resolves a [GroupMsgType] by its wire [code].
         *
         * @return The corresponding [GroupMsgType], or [MUSIC] if unknown.
         */
        @JvmStatic
        fun fromCode(code: Int): GroupMsgType =
            entries.firstOrNull { it.code == code } ?: MUSIC
    }
}
