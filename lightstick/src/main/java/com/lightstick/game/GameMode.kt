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
 * Parsed game result received via FF04 Notify from the relay (`GameMode_Spec_v2_7.docx` §2.4).
 *
 * FF04 carries two different notifications sharing this one shape, told apart by [cmdIndex]:
 * - [CMD_RESULT] (5): a real game result. For Mode 1/2, [redScore] is the individual score
 *   (0–5) and [blueScore] is always 0; for Mode 3/4, [redScore]/[blueScore] are team totals —
 *   compare them to determine the winner. [wandId] is the reporting wand's id (Mode 1/2 only;
 *   Mode 3/4 send 0). Use [isWandIdValid] to check it.
 * - [CMD_TEAM_CONFIRM] (8): Mode 4 only, an aggregated Notify sent after
 *   `Device.sendTeamAssignEnd` — [totalCount] is the confirmed headcount for the team that was
 *   just ended, and [wandId] is *reinterpreted* as that team's id (0=RED/1=BLUE), not a wand
 *   identifier — [isWandIdValid] is meaningless here and always reports `false`.
 *   [redScore]/[blueScore] are unused (0) for this notification.
 *
 * @property mode        Game mode this result belongs to.
 * @property cmdIndex    [CMD_RESULT] or [CMD_TEAM_CONFIRM] — determines how the other fields
 *                        below are interpreted.
 * @property redScore    Red-team cumulative score (or individual score for Mode 1/2). Unused
 *                        (0) for [CMD_TEAM_CONFIRM].
 * @property blueScore   Blue-team cumulative score (always 0 for Mode 1/2). Unused (0) for
 *                        [CMD_TEAM_CONFIRM].
 * @property totalCount  [CMD_RESULT]: number of wands that reported so far (Mode 1/2).
 *                        [CMD_TEAM_CONFIRM]: confirmed headcount for the just-ended team.
 * @property wandId      [CMD_RESULT]: wand identifier (lower 2 bytes of MAC), 0x0000/0xFFFF =
 *                        invalid. [CMD_TEAM_CONFIRM]: team id (0=RED/1=BLUE) — not a wand id.
 */
data class GameResult(
    val mode: GameMode,
    val cmdIndex: Int,
    val redScore: Int,
    val blueScore: Int,
    val totalCount: Int,
    val wandId: Int
) {
    /** `false` for [CMD_TEAM_CONFIRM], or if [wandId] is the reserved invalid value 0x0000/0xFFFF. */
    val isWandIdValid: Boolean
        get() = cmdIndex == CMD_RESULT && wandId != 0x0000 && wandId != 0xFFFF

    companion object {
        /** [cmdIndex] for an individual game result (score) Notify. */
        const val CMD_RESULT: Int = 5

        /** [cmdIndex] for a Mode 4 aggregated team-assignment Notify (see class doc). */
        const val CMD_TEAM_CONFIRM: Int = 8
    }
}
