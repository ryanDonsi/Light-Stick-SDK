package com.lightstick.test

import com.lightstick.group.GroupPalette
import com.lightstick.types.Color
import com.lightstick.types.Colors
import com.lightstick.types.EffectType
import com.lightstick.types.LSEffectPayload
import com.lightstick.types.MsgType
import org.junit.Assert.*
import org.junit.Test

/**
 * Group protocol (Glowsync group mapping spec v2.2) unit tests — no BLE connection needed.
 */
class GroupProtocolTest {

    // ===========================================================================================
    // GroupPalette
    // ===========================================================================================

    @Test
    fun testPaletteSize() {
        assertEquals(20, GroupPalette.PALETTE.size)
    }

    @Test
    fun testPaletteKnownValues() {
        // Spot-check against the corrected v2.2 table (spec §4).
        assertEquals(Color(255, 0, 0), GroupPalette.colorFor(1))
        assertEquals(Color(0, 255, 74), GroupPalette.colorFor(2))
        assertEquals(Color(149, 0, 255), GroupPalette.colorFor(3))
        assertEquals(Color(255, 223, 0), GroupPalette.colorFor(4))
        assertEquals(Color(116, 255, 0), GroupPalette.colorFor(20))
    }

    @Test
    fun testPaletteOutOfRangeThrows() {
        assertThrows(IllegalArgumentException::class.java) { GroupPalette.colorFor(0) }
        assertThrows(IllegalArgumentException::class.java) { GroupPalette.colorFor(21) }
    }

    @Test
    fun testPaletteHasNoDuplicateColors() {
        // Golden-angle spacing should keep all 20 groups visually distinct.
        assertEquals(GroupPalette.PALETTE.size, GroupPalette.PALETTE.toSet().size)
    }

    // ===========================================================================================
    // MsgType
    // ===========================================================================================

    @Test
    fun testMsgTypeCodes() {
        assertEquals(0, MsgType.MUSIC.code)
        assertEquals(1, MsgType.GAME.code)
        assertEquals(2, MsgType.GROUP_SETUP.code)
        assertEquals(3, MsgType.GROUP_CONTROL.code)
    }

    @Test
    fun testMsgTypeFromCode() {
        assertEquals(MsgType.GROUP_SETUP, MsgType.fromCode(2))
        assertEquals(MsgType.GROUP_CONTROL, MsgType.fromCode(3))
        assertEquals(MsgType.MUSIC, MsgType.fromCode(99)) // unknown -> MUSIC
    }

    // ===========================================================================================
    // LSEffectPayload.Group.setup
    // ===========================================================================================

    @Test
    fun testSetupPayloadFields() {
        val payload = LSEffectPayload.Group.setup(groupId = 3)

        assertEquals(MsgType.GROUP_SETUP, payload.msgType)
        assertEquals(3, payload.groupId)
        assertEquals(GroupPalette.colorFor(3), payload.color)
        assertEquals(Colors.BLACK, payload.backgroundColor)
        assertEquals(EffectType.BLINK, payload.effectType)
        assertEquals(6, payload.period)
        assertEquals(100, payload.spf)
    }

    @Test
    fun testSetupPayloadBytes() {
        val bytes = LSEffectPayload.Group.setup(groupId = 5).toByteArray()

        assertEquals(20, bytes.size)
        assertEquals(0, bytes[0].toInt()); assertEquals(0, bytes[1].toInt()) // effectIndex = 0
        assertEquals(2, bytes[2].toInt())   // msgType = GroupSetup
        assertEquals(5, bytes[3].toInt())   // groupId
        val fg = GroupPalette.colorFor(5)
        assertEquals(fg.r, bytes[4].toInt() and 0xFF)
        assertEquals(fg.g, bytes[5].toInt() and 0xFF)
        assertEquals(fg.b, bytes[6].toInt() and 0xFF)
        assertEquals(3, bytes[10].toInt())  // effectType = BLINK
        assertEquals(6, bytes[13].toInt())  // period
        assertEquals(100, bytes[14].toInt() and 0xFF) // spf
        assertEquals(0, bytes[15].toInt())  // randomColor fixed 0
        assertEquals(1, bytes[16].toInt())  // randomDelay fixed 1
        assertEquals(0, bytes[17].toInt())  // fadeValue fixed 0
        assertEquals(0, bytes[18].toInt())  // broadcasting fixed 0
    }

    @Test
    fun testSetupRejectsGroupZeroAndOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) { LSEffectPayload.Group.setup(0) }
        assertThrows(IllegalArgumentException::class.java) { LSEffectPayload.Group.setup(21) }
    }

    // ===========================================================================================
    // LSEffectPayload.Group.control
    // ===========================================================================================

    @Test
    fun testControlDefaultsToGroupPaletteColor() {
        val payload = LSEffectPayload.Group.control(groupId = 7, effectType = EffectType.ON)
        assertEquals(GroupPalette.colorFor(7), payload.color)
    }

    @Test
    fun testControlAllGroupsDefaultsToWhite() {
        val payload = LSEffectPayload.Group.control(groupId = 0, effectType = EffectType.OFF)
        assertEquals(Colors.WHITE, payload.color)
    }

    @Test
    fun testControlExplicitColorOverridesDefault() {
        val payload = LSEffectPayload.Group.control(groupId = 1, effectType = EffectType.ON, color = Colors.CYAN)
        assertEquals(Colors.CYAN, payload.color)
    }

    @Test
    fun testControlMsgTypeAndByteLayout() {
        val bytes = LSEffectPayload.Group.control(groupId = 0, effectType = EffectType.BLINK).toByteArray()
        assertEquals(20, bytes.size)
        assertEquals(3, bytes[2].toInt()) // msgType = GroupControl
        assertEquals(0, bytes[3].toInt()) // groupId = all
        assertEquals(3, bytes[10].toInt()) // effectType = BLINK
    }

    @Test
    fun testControlRejectsOutOfRangeGroupId() {
        assertThrows(IllegalArgumentException::class.java) {
            LSEffectPayload.Group.control(groupId = 21, effectType = EffectType.ON)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LSEffectPayload.Group.control(groupId = -1, effectType = EffectType.ON)
        }
    }

    // ===========================================================================================
    // defaultPeriodSpf timing table (spec §3.3)
    // ===========================================================================================

    @Test
    fun testDefaultPeriodSpfTable() {
        assertEquals(0 to 100, LSEffectPayload.Group.defaultPeriodSpf(EffectType.OFF))
        assertEquals(0 to 100, LSEffectPayload.Group.defaultPeriodSpf(EffectType.ON))
        assertEquals(10 to 20, LSEffectPayload.Group.defaultPeriodSpf(EffectType.STROBE))
        assertEquals(6 to 100, LSEffectPayload.Group.defaultPeriodSpf(EffectType.BLINK))
        assertEquals(20 to 100, LSEffectPayload.Group.defaultPeriodSpf(EffectType.BREATH))
    }

    @Test
    fun testControlUsesDefaultTimingWhenNotProvided() {
        val strobe = LSEffectPayload.Group.control(groupId = 1, effectType = EffectType.STROBE)
        assertEquals(10, strobe.period)
        assertEquals(20, strobe.spf)
    }

    @Test
    fun testControlHonorsExplicitTimingOverride() {
        val strobe =
            LSEffectPayload.Group.control(groupId = 1, effectType = EffectType.STROBE, period = 99, spf = 50)
        assertEquals(99, strobe.period)
        assertEquals(50, strobe.spf)
    }

    // ===========================================================================================
    // Round-trip
    // ===========================================================================================

    @Test
    fun testRoundTripSetup() {
        val original = LSEffectPayload.Group.setup(groupId = 12)
        val decoded = LSEffectPayload.fromByteArray(original.toByteArray())

        assertEquals(original.msgType, decoded.msgType)
        assertEquals(original.groupId, decoded.groupId)
        assertEquals(original.color, decoded.color)
        assertEquals(original.backgroundColor, decoded.backgroundColor)
        assertEquals(original.effectType, decoded.effectType)
        assertEquals(original.period, decoded.period)
        assertEquals(original.spf, decoded.spf)
    }

    @Test
    fun testRoundTripControl() {
        val original = LSEffectPayload.Group.control(
            groupId = 0,
            effectType = EffectType.BREATH,
            color = Colors.PINK,
            backgroundColor = Colors.BLUE,
            durationMs = 4000
        )
        val decoded = LSEffectPayload.fromByteArray(original.toByteArray())

        assertEquals(original.msgType, decoded.msgType)
        assertEquals(original.groupId, decoded.groupId)
        assertEquals(original.color, decoded.color)
        assertEquals(original.backgroundColor, decoded.backgroundColor)
        assertEquals(original.effectType, decoded.effectType)
        assertEquals(original.durationMs, decoded.durationMs)
    }

    @Test
    fun testInvalidByteLengthThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            LSEffectPayload.fromByteArray(ByteArray(19))
        }
    }
}
