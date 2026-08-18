package com.lightstick.samples

import com.lightstick.group.GroupPalette
import com.lightstick.types.EffectType
import com.lightstick.types.LSEffectPayload

/**
 * Group protocol (Glowsync group mapping spec v2.2) usage samples.
 */
object GroupSamples {

    fun sampleGroupSetup() {
        // Broadcast "join group 3" — unassigned lightsticks blink group 3's palette color.
        val setup = LSEffectPayload.Group.setup(groupId = 3)
        println(setup.color) // GroupPalette.colorFor(3) == Color(149, 0, 255)
        setup.toByteArray()
    }

    fun sampleGroupControl() {
        // Play BLINK on group 3 only, using group 3's own palette color.
        val single = LSEffectPayload.Group.control(groupId = 3, effectType = EffectType.BLINK)

        // Turn everything off.
        val allOff = LSEffectPayload.Group.control(groupId = 0, effectType = EffectType.OFF)

        single.toByteArray()
        allOff.toByteArray()
    }

    fun samplePalette() {
        for (groupId in GroupPalette.MIN_GROUP_ID..GroupPalette.MAX_GROUP_ID) {
            println("group $groupId -> ${GroupPalette.colorFor(groupId)}")
        }
    }
}
