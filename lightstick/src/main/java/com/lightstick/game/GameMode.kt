package com.lightstick.game

/**
 * Game mode supported by the wand / relay system.
 *
 * @property subIndex Protocol subIndex value sent in the FF03 command payload.
 */
enum class GameMode(val subIndex: Int) {
    /** Mode 1 — LED ON → shake fast. First to 5 wins. */
    SPEED_REACTION(1),

    /** Mode 2 — Match the LED tempo. 5 consecutive hits within 20 s wins. */
    TEMPO(2),

    /** Mode 3 — Red vs Blue team battle over 5 rounds. */
    TEAM_BATTLE(3);

    companion object {
        fun fromSubIndex(subIndex: Int): GameMode? = entries.find { it.subIndex == subIndex }
    }

    /**
     * Maximum time (ms) the app should wait for a [GameResult] Notify after calling
     * [Device.startGame], per spec §3. Includes the 2-second auto-start delay plus
     * a 2-second safety margin.
     *
     * Usage:
     * ```kotlin
     * val timeout = GameMode.SPEED_REACTION.resultTimeoutMs(GameLevel.NORMAL)
     * // start a coroutine timeout or Handler.postDelayed with this value
     * ```
     */
    fun resultTimeoutMs(level: GameLevel): Long = when (this) {
        SPEED_REACTION -> when (level) {
            GameLevel.EASY   -> 64_000L   // 2 + 60 + 2
            GameLevel.NORMAL -> 44_000L   // 2 + 40 + 2
            GameLevel.HARD   -> 24_000L   // 2 + 20 + 2
        }
        TEMPO -> 24_000L                  // 2 + 20 + 2 (level-independent)
        TEAM_BATTLE -> when (level) {
            GameLevel.EASY   -> 36_000L   // 2 + 30 + 2 + 2
            GameLevel.NORMAL -> 31_000L   // 2 + 25 + 2 + 2
            GameLevel.HARD   -> 26_000L   // 2 + 20 + 2 + 2
        }
    }
}

/**
 * Difficulty level passed in the FF03 command payload.
 *
 * @property value Protocol level byte (1 = easy, 2 = normal, 3 = hard).
 */
enum class GameLevel(val value: Int) {
    EASY(1),
    NORMAL(2),
    HARD(3)
}

/**
 * A single wand's game result, received via one FF04 Notify from the relay (protocol v2.0
 * "레이아웃 B" — one Notify per reporting wand, not a team-aggregated packet).
 *
 * There is no team-color field on the wire: for [GameMode.TEAM_BATTLE] (Mode 3), the app must
 * aggregate [score] per [wandId] into red/blue totals itself, using its own wand-to-team
 * assignment (e.g. from however teams were assigned at game start).
 *
 * @property mode    Game mode this result belongs to.
 * @property score   This wand's individual score for the round (0–5).
 * @property wandId  Wand identifier (lower 2 bytes of MAC). 0x0000 / 0xFFFF = invalid.
 * @property msgId   Burst-dedup sequence number from the wire. 802.15.4 repeats each result 3x
 *                    and the relay already dedups before forwarding via FF04, so this is
 *                    normally just diagnostic — a safety net if a duplicate ever slips through.
 */
data class GameResult(
    val mode: GameMode,
    val score: Int,
    val wandId: Int,
    val msgId: Int
) {
    /** `false` if [wandId] is the reserved invalid value 0x0000 or 0xFFFF. */
    val isWandIdValid: Boolean
        get() = wandId != 0x0000 && wandId != 0xFFFF
}
