package com.lightstick.samples

import com.lightstick.group.GroupPalette
import com.lightstick.types.EffectType
import com.lightstick.types.LSEffectPayload

/**
 * Group protocol (Glowsync group mapping spec v2.0) usage samples.
 */
object GroupSamples {

    fun sampleGroupSetup() {
        // Broadcast "join group 3" — unassigned lightsticks blink group 3's palette color.
        val setup = LSEffectPayload.Group.setup(groupId = 3)
        println(setup.color) // GroupPalette.colorFor(3) == Color(149, 0, 255)
        setup.toByteArray()
    }

    fun sampleGroupControl() {
        // Play BLINK on group 3 only.
        val single = LSEffectPayload.Group.control(
            groupMask = LSEffectPayload.Group.GRP3,
            effectType = EffectType.BLINK,
            color = com.lightstick.types.Colors.WHITE
        )

        // Play BLINK on group 1 + group 3 together — one packet, both react at once
        // (unlike a sequential wave of separate single-group messages).
        val combo = LSEffectPayload.Group.control(
            groupMask = LSEffectPayload.Group.GRP1 or LSEffectPayload.Group.GRP3,
            effectType = EffectType.BLINK,
            color = com.lightstick.types.Colors.WHITE
        )

        // Turn everything off — every connected lightstick, regardless of group assignment.
        val allOff = LSEffectPayload.Group.control(
            groupMask = LSEffectPayload.Group.ALL_SINGLE,
            effectType = EffectType.OFF,
            color = com.lightstick.types.Colors.WHITE
        )

        single.toByteArray()
        combo.toByteArray()
        allOff.toByteArray()
    }

    fun samplePalette() {
        for (groupId in GroupPalette.MIN_GROUP_ID..GroupPalette.MAX_GROUP_ID) {
            println("group $groupId -> ${GroupPalette.colorFor(groupId)}")
        }
    }
}
