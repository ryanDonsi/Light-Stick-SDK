package com.lightstick.control

/**
 * Show-control command sent to FF0A via `Device.sendControl` (BLE protocol v2.2 §2.6.3).
 *
 * All commands are idempotent on the wand side (a command only acts when meaningful in the
 * current state), so resending is safe. There is no ACK — see the protocol document.
 *
 * @property cmdIndex Protocol cmdIndex value written at offset 5 of the control frame.
 * @since 2.200.0
 */
enum class ControlCmd(val cmdIndex: Int) {
    /** Enter show mode (button input restricted). Does not change the LED. */
    SHOW_ENTER(1),

    /** Leave show mode. If paused, also un-pauses and turns the LED off. */
    SHOW_EXIT(2),

    /** Freeze the current effect on its current color (also stops the duration timer). */
    PAUSE(3),

    /** Restart the last effect from the beginning. Only acts while paused. */
    RESUME(4),

    /**
     * Powers the wand off. **Irreversible remotely** — the wand can only be turned back on by
     * its button. Always confirm with the user before sending.
     */
    SYSTEM_OFF(5)
}
