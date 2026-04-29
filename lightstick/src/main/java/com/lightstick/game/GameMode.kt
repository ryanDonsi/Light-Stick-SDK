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
 * Parsed game result received via FF04 Notify from the relay.
 *
 * For Mode 1 / Mode 2: [redScore] holds the individual score (0–5); [blueScore] is always 0.
 * For Mode 3: [redScore] and [blueScore] are team totals; compare to determine the winner.
 *
 * @property mode        Game mode this result belongs to.
 * @property redScore    Red-team cumulative score (or individual score for Mode 1/2).
 * @property blueScore   Blue-team cumulative score (always 0 for Mode 1/2).
 * @property totalCount  Number of wands that reported results (participant count).
 * @property wandId      Wand identifier (lower 2 bytes of MAC). 0x0000 / 0xFFFF = invalid.
 */
data class GameResult(
    val mode: GameMode,
    val redScore: Int,
    val blueScore: Int,
    val totalCount: Int,
    val wandId: Int
) {
    /** `false` if [wandId] is the reserved invalid value 0x0000 or 0xFFFF. */
    val isWandIdValid: Boolean
        get() = wandId != 0x0000 && wandId != 0xFFFF
}
