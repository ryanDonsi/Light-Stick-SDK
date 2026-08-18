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
 * Group protocol (Glowsync group mapping spec v2.0, msgType-only discriminator + groupMask)
 * unit tests — no BLE connection needed.
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
        assertEquals(1, MsgType.GAME_MODE.code)
        assertEquals(2, MsgType.GROUP_SETUP.code)
    }

    @Test
    fun testMsgTypeFromCode() {
        assertEquals(MsgType.GROUP_SETUP, MsgType.fromCode(2))
        assertEquals(MsgType.MUSIC, MsgType.fromCode(99)) // unknown -> MUSIC
    }

    // ===========================================================================================
    // LSEffectPayload.Group GRP* constants
    // ===========================================================================================

    @Test
    fun testGrpConstants() {
        assertEquals(0b1L, LSEffectPayload.Group.GRP1)
        assertEquals(0b100L, LSEffectPayload.Group.GRP3)
        assertEquals(1L shl 31, LSEffectPayload.Group.GRP32)
    }

    @Test
    fun testGrpConstantsCombine() {
        // group 1 + group 3
        assertEquals(0b101L, LSEffectPayload.Group.GRP1 or LSEffectPayload.Group.GRP3)
    }

    @Test
    fun testMaskAllConstants() {
        assertEquals(0L, LSEffectPayload.Group.ALL_SINGLE)
        assertEquals(0xFFFFFFFFL, LSEffectPayload.Group.ALL_GROUPS)
    }

    // ===========================================================================================
    // LSEffectPayload.Group.setup
    // ===========================================================================================

    @Test
    fun testSetupPayloadFields() {
        val payload = LSEffectPayload.Group.setup(groupId = 3)

        assertEquals(MsgType.GROUP_SETUP, payload.msgType)
        assertEquals(LSEffectPayload.Group.GRP3, payload.groupMask)
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
        assertEquals(2, bytes[0].toInt())   // msgType = GroupSetup
        // groupMask (offset 1-4, u32 LE): bit(5-1)=bit4 set -> 0x00000010
        assertEquals(0x10, bytes[1].toInt() and 0xFF)
        assertEquals(0, bytes[2].toInt()); assertEquals(0, bytes[3].toInt()); assertEquals(0, bytes[4].toInt())
        val fg = GroupPalette.colorFor(5)
        assertEquals(fg.r, bytes[5].toInt() and 0xFF)
        assertEquals(fg.g, bytes[6].toInt() and 0xFF)
        assertEquals(fg.b, bytes[7].toInt() and 0xFF)
        assertEquals(3, bytes[11].toInt())  // effectType = BLINK
        assertEquals(6, bytes[12].toInt())  // period
        assertEquals(100, bytes[13].toInt() and 0xFF) // spf
        assertEquals(0, bytes[14].toInt())  // randomColor fixed 0
        assertEquals(1, bytes[15].toInt())  // randomDelay fixed 1
        assertEquals(0, bytes[16].toInt())  // fadeValue fixed 0
        assertEquals(0, bytes[17].toInt())  // broadcasting fixed 0
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
    fun testControlSingleGroupMask() {
        val payload = LSEffectPayload.Group.control(
            groupMask = LSEffectPayload.Group.GRP7,
            effectType = EffectType.ON,
            color = Colors.CYAN
        )
        assertEquals(Colors.CYAN, payload.color)
        assertEquals(LSEffectPayload.Group.GRP7, payload.groupMask)
        assertEquals(MsgType.MUSIC, payload.msgType)
    }

    @Test
    fun testControlCombinesGrpConstantsWithOr() {
        val payload = LSEffectPayload.Group.control(
            groupMask = LSEffectPayload.Group.GRP1 or LSEffectPayload.Group.GRP3,
            effectType = EffectType.BLINK,
            color = Colors.WHITE
        )
        assertEquals(LSEffectPayload.Group.GRP1 or LSEffectPayload.Group.GRP3, payload.groupMask)
        assertEquals(MsgType.MUSIC, payload.msgType)
    }

    @Test
    fun testControlAllSingleUsesMaskZero() {
        val payload = LSEffectPayload.Group.control(
            groupMask = LSEffectPayload.Group.ALL_SINGLE,
            effectType = EffectType.OFF,
            color = Colors.WHITE
        )
        assertEquals(LSEffectPayload.Group.ALL_SINGLE, payload.groupMask)
        assertEquals(MsgType.MUSIC, payload.msgType)
    }

    @Test
    fun testControlAllGroupsUsesMaskAllBits() {
        val payload = LSEffectPayload.Group.control(
            groupMask = LSEffectPayload.Group.ALL_GROUPS,
            effectType = EffectType.OFF,
            color = Colors.WHITE
        )
        assertEquals(LSEffectPayload.Group.ALL_GROUPS, payload.groupMask)
    }

    @Test
    fun testControlMsgTypeAndByteLayout() {
        val bytes = LSEffectPayload.Group.control(
            groupMask = LSEffectPayload.Group.ALL_SINGLE,
            effectType = EffectType.BLINK,
            color = Colors.WHITE
        ).toByteArray()
        assertEquals(20, bytes.size)
        assertEquals(0, bytes[0].toInt()) // msgType = MUSIC (no more dedicated GroupControl msgType)
        assertEquals(0, bytes[1].toInt()); assertEquals(0, bytes[2].toInt())
        assertEquals(0, bytes[3].toInt()); assertEquals(0, bytes[4].toInt()) // groupMask = 0 (all/single)
        assertEquals(3, bytes[11].toInt()) // effectType = BLINK
    }

    @Test
    fun testControlGroupsMaskByteLayout() {
        val bytes = LSEffectPayload.Group.control(
            groupMask = LSEffectPayload.Group.GRP1 or LSEffectPayload.Group.GRP3,
            effectType = EffectType.ON,
            color = Colors.WHITE
        ).toByteArray()
        // groupMask (offset 1-4, u32 LE): bit0 | bit2 = 0x00000005
        assertEquals(0x05, bytes[1].toInt() and 0xFF)
        assertEquals(0, bytes[2].toInt()); assertEquals(0, bytes[3].toInt()); assertEquals(0, bytes[4].toInt())
    }

    // ===========================================================================================
    // Default timing table (via control())
    // ===========================================================================================

    @Test
    fun testControlUsesDefaultTimingWhenNotProvided() {
        val off = LSEffectPayload.Group.control(LSEffectPayload.Group.GRP1, EffectType.OFF, Colors.WHITE)
        assertEquals(0, off.period); assertEquals(100, off.spf)

        val on = LSEffectPayload.Group.control(LSEffectPayload.Group.GRP1, EffectType.ON, Colors.WHITE)
        assertEquals(0, on.period); assertEquals(100, on.spf)

        val strobe = LSEffectPayload.Group.control(LSEffectPayload.Group.GRP1, EffectType.STROBE, Colors.WHITE)
        assertEquals(10, strobe.period); assertEquals(20, strobe.spf)

        val blink = LSEffectPayload.Group.control(LSEffectPayload.Group.GRP1, EffectType.BLINK, Colors.WHITE)
        assertEquals(6, blink.period); assertEquals(100, blink.spf)

        val breath = LSEffectPayload.Group.control(LSEffectPayload.Group.GRP1, EffectType.BREATH, Colors.WHITE)
        assertEquals(20, breath.period); assertEquals(100, breath.spf)
    }

    @Test
    fun testControlHonorsExplicitTimingOverride() {
        val strobe = LSEffectPayload.Group.control(
            groupMask = LSEffectPayload.Group.GRP1,
            effectType = EffectType.STROBE,
            color = Colors.WHITE,
            period = 99,
            spf = 50
        )
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
        assertEquals(original.groupMask, decoded.groupMask)
        assertEquals(original.color, decoded.color)
        assertEquals(original.backgroundColor, decoded.backgroundColor)
        assertEquals(original.effectType, decoded.effectType)
        assertEquals(original.period, decoded.period)
        assertEquals(original.spf, decoded.spf)
    }

    @Test
    fun testRoundTripControl() {
        val original = LSEffectPayload.Group.control(
            groupMask = LSEffectPayload.Group.GRP2 or LSEffectPayload.Group.GRP5,
            effectType = EffectType.BREATH,
            color = Colors.PINK,
            backgroundColor = Colors.BLUE
        )
        val decoded = LSEffectPayload.fromByteArray(original.toByteArray())

        assertEquals(original.msgType, decoded.msgType)
        assertEquals(original.groupMask, decoded.groupMask)
        assertEquals(original.color, decoded.color)
        assertEquals(original.backgroundColor, decoded.backgroundColor)
        assertEquals(original.effectType, decoded.effectType)
    }

    @Test
    fun testInvalidByteLengthThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            LSEffectPayload.fromByteArray(ByteArray(19))
        }
    }
}
