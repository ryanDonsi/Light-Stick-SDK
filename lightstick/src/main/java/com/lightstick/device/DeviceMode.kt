package com.lightstick.device

/**
 * Current operating mode reported by FF06 (Read-only), per spec v2.8 §2.1.
 *
 * BLE mode is aimed at home-party / small-gathering use and does not support group control
 * (1~20 groups) or [com.lightstick.game.GameMode.TEAM_SIMULTANEOUS] (Mode 4) — apps should
 * read this once right after connecting and disable those menus when [value] is `BLE`.
 *
 * @property value Protocol byte value (0=relay/중계기, 1=BLE Only).
 */
enum class DeviceMode(val value: Int) {
    /** 중계기 모드 — all features supported. */
    RELAY(0),

    /** BLE Only 모드 — group control and Mode 4 are not supported. */
    BLE(1);

    companion object {
        fun fromValue(value: Int): DeviceMode? = entries.find { it.value == value }
    }
}
