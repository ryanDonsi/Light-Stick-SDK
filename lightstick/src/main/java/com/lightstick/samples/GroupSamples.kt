package com.lightstick.samples

import com.lightstick.group.GroupPalette
import com.lightstick.types.EffectType
import com.lightstick.types.Group
import com.lightstick.types.LSEffectPayload

/**
 * Group protocol (Glowsync group mapping spec v2.0) usage samples.
 *
 * GroupSetup ("join group N") is sent via `Device.sendGroupSetting(groupId)` — see that
 * method's KDoc, since it needs a connected [com.lightstick.device.Device] to demonstrate.
 * The samples below cover group *control*, which is just an [LSEffectPayload] with a
 * `groupMask`, sent via the same `Device.sendEffect` as any other effect.
 */
object GroupSamples {

    fun sampleGroupControl() {
        // Play BLINK on group 3 only.
        val single = LSEffectPayload(
            groupMask = Group.GRP3,
            effectType = EffectType.BLINK,
            color = com.lightstick.types.Colors.WHITE
        )

        // Play BLINK on group 1 + group 3 together — one packet, both react at once
        // (unlike a sequential wave of separate single-group messages).
        val combo = LSEffectPayload(
            groupMask = Group.GRP1 or Group.GRP3,
            effectType = EffectType.BLINK,
            color = com.lightstick.types.Colors.WHITE
        )

        // Turn everything off — every connected lightstick, regardless of group assignment.
        // groupMask defaults to ALL_SINGLE, so it can be omitted entirely.
        val allOff = LSEffectPayload(effectType = EffectType.OFF, color = com.lightstick.types.Colors.WHITE)

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
