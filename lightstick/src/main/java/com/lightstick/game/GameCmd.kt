package com.lightstick.game

/**
 * Game command sent to FF03 via `Device.sendGameCmd(cmd, ...)` — the single entry point for
 * every game command, replacing what used to be five separate `Device` methods
 * (`startGame`/`stopGame`/`clearGame`/`sendWinner`/`sendTeamAssign`/`sendTeamAssignEnd`).
 *
 * Sending a command and receiving its result are decoupled: `sendGameCmd` only writes to FF03
 * and returns once the write is enqueued — results (including the [GameCmd.TEAM_ASSIGN_END]
 * confirmation) arrive later through `Device.subscribeGameResults`'s `onResult` callback, told
 * apart by [com.lightstick.game.GameResult.cmdIndex].
 *
 * @property cmdIndex Protocol cmdIndex value sent in the FF03 command payload.
 */
enum class GameCmd(val cmdIndex: Int) {
    /**
     * Starts a game. Needs `mode` and `level` (difficulty, or Mode 4 round count) and
     * `option` (Mode 3 random-team sentinel, or Mode 4 per-round measure time in ms).
     */
    START(1),

    /** Aborts a running game immediately. No extra params needed. */
    STOP(3),

    /** Resets the device to idle. No extra params needed. */
    CLEAR(4),

    /** Announces the winning wand. Needs `mode` (Mode 1/2 only) and `wandId`. */
    WINNER(6),

    /**
     * Mode 4 only: starts/continues team assignment for a team — unassigned wands blink that
     * team's color, pressing a wand's button locks it in. Needs `level` = team id (0=RED/1=BLUE).
     */
    TEAM_ASSIGN(7),

    /**
     * Mode 4 only: ends assignment for a team. Not forwarded to wands over 802.15.4 — the relay
     * handles it locally and replies with an aggregated result Notify carrying that team's
     * confirmed headcount. Needs `level` = team id (0=RED/1=BLUE).
     */
    TEAM_ASSIGN_END(9)
}
