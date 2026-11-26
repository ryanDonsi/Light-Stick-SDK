package com.lightstick.internal.ble

/** Pure layout-C Control frame helpers (BLE protocol v2.2 §2.6.1). Public for unit tests only. */
object ControlFrame {
    const val MSG_TYPE_CONTROL = 3
    const val FRAME_SIZE = 20

    // cmdIndex codes (§2.6.3) — mirrored by the public ControlCmd enum.
    const val CMD_PAUSE = 3
    const val CMD_RESUME = 4
    private const val EFFECT_INDEX_MAX = 0xFF

    /** Builds a 20-byte layout-C frame: msgType@0, groupMask@1-4 (u32 LE), cmdIndex@5, effectIndex@17. */
    @JvmStatic
    fun build(cmdIndex: Int, groupMask: Long, effectIndex: Int): ByteArray {
        require(cmdIndex in 0..0xFF) { "cmdIndex must be within 0..255" }
        require(groupMask in 0L..0xFFFFFFFFL) { "groupMask must be within 0..0xFFFFFFFF" }
        require(effectIndex in 0..EFFECT_INDEX_MAX) { "effectIndex must be within 0..255" }
        return ByteArray(FRAME_SIZE).also { b ->
            b[0] = MSG_TYPE_CONTROL.toByte()
            b[1] = (groupMask and 0xFF).toByte()
            b[2] = ((groupMask ushr 8) and 0xFF).toByte()
            b[3] = ((groupMask ushr 16) and 0xFF).toByte()
            b[4] = ((groupMask ushr 24) and 0xFF).toByte()
            b[5] = cmdIndex.toByte()
            b[17] = effectIndex.toByte()
        }
    }

    /** 1..255 cyclic (0 skipped, same convention as the timeline effectIndex). */
    @JvmStatic
    fun nextEffectIndex(current: Int): Int = (current % EFFECT_INDEX_MAX) + 1
}
