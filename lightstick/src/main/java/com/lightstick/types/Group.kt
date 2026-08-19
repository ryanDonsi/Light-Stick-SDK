package com.lightstick.types

/**
 * Group targeting bitmask constants (Glowsync group mapping spec v2.0).
 *
 * `groupMask` (offset 1-4 of the 20-byte frame — see the [LSEffectPayload] class doc) is a
 * 32-bit bitmask where bit(N-1) selects group N (1..32); combine groups with plain `or`
 * (e.g. `GRP1 or GRP3`) so every targeted lightstick reacts to the same 802.15.4 packet at
 * once — unlike a sequential "wave" of separate messages, which is the caller's (app's)
 * responsibility to sequence. [ALL_SINGLE] means single/all (ignore group membership
 * entirely); [ALL_GROUPS] means every group-assigned lightstick.
 *
 * There is no separate "GroupControl" msgType or method — group control is an ordinary
 * [LSEffectPayload] (default [MsgType.EFFECT]) with one of these constants as `groupMask`,
 * sent via [Device.sendEffect] exactly like a non-group effect.
 *
 * @sample com.lightstick.samples.GroupSamples.sampleGroupControl
 * @since 1.6.0
 */
object Group {

    const val GRP1: Long = 1L shl 0
    const val GRP2: Long = 1L shl 1
    const val GRP3: Long = 1L shl 2
    const val GRP4: Long = 1L shl 3
    const val GRP5: Long = 1L shl 4
    const val GRP6: Long = 1L shl 5
    const val GRP7: Long = 1L shl 6
    const val GRP8: Long = 1L shl 7
    const val GRP9: Long = 1L shl 8
    const val GRP10: Long = 1L shl 9
    const val GRP11: Long = 1L shl 10
    const val GRP12: Long = 1L shl 11
    const val GRP13: Long = 1L shl 12
    const val GRP14: Long = 1L shl 13
    const val GRP15: Long = 1L shl 14
    const val GRP16: Long = 1L shl 15
    const val GRP17: Long = 1L shl 16
    const val GRP18: Long = 1L shl 17
    const val GRP19: Long = 1L shl 18
    const val GRP20: Long = 1L shl 19
    const val GRP21: Long = 1L shl 20
    const val GRP22: Long = 1L shl 21
    const val GRP23: Long = 1L shl 22
    const val GRP24: Long = 1L shl 23
    const val GRP25: Long = 1L shl 24
    const val GRP26: Long = 1L shl 25
    const val GRP27: Long = 1L shl 26
    const val GRP28: Long = 1L shl 27
    const val GRP29: Long = 1L shl 28
    const val GRP30: Long = 1L shl 29
    const val GRP31: Long = 1L shl 30
    const val GRP32: Long = 1L shl 31

    /** Mask value meaning "single/all — ignore group membership entirely" (spec: `0`). */
    const val ALL_SINGLE: Long = 0L

    /** Mask value meaning "every group" — all 32 bits set (spec: `0xFFFFFFFF`). */
    const val ALL_GROUPS: Long = 0xFFFFFFFFL
}
