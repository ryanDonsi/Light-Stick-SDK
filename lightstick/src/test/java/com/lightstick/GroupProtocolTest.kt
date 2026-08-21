package com.lightstick.test

import com.lightstick.types.Color
import com.lightstick.types.Colors
import com.lightstick.types.EffectType
import com.lightstick.types.Group
import com.lightstick.types.GroupPalette
import com.lightstick.types.LSEffectPayload
import com.lightstick.types.MsgType
import org.junit.Assert.*
import org.junit.Test

/**
 * Group protocol (Glowsync group mapping spec v2.0, msgType-only discriminator + groupMask)
 * unit tests — no BLE connection needed.
 *
 * Group *control* is an ordinary [LSEffectPayload] (see [testControl*][testControlSingleGroupMask]
 * tests below); GroupSetup frame assembly now lives in `Device.sendGroupSetting`, which needs
 * Android framework types and isn't reachable from this plain-JVM test module — the
 * [testGroupSetupWireFormat] test below mirrors that method's field values to at least pin down
 * the wire encoding.
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
        assertEquals(0, MsgType.EFFECT.code)
        assertEquals(1, MsgType.GAME_MODE.code)
        assertEquals(2, MsgType.GROUP_SETUP.code)
    }

    @Test
    fun testMsgTypeFromCode() {
        assertEquals(MsgType.GROUP_SETUP, MsgType.fromCode(2))
        assertEquals(MsgType.EFFECT, MsgType.fromCode(99)) // unknown -> EFFECT
    }

    // ===========================================================================================
    // Group GRP* constants
    // ===========================================================================================

    @Test
    fun testGrpConstants() {
        assertEquals(0b1L, Group.GRP1)
        assertEquals(0b100L, Group.GRP3)
        assertEquals(1L shl 31, Group.GRP32)
    }

    @Test
    fun testGrpConstantsCombine() {
        // group 1 + group 3
        assertEquals(0b101L, Group.GRP1 or Group.GRP3)
    }

    @Test
    fun testMaskAllConstants() {
        assertEquals(0L, Group.ALL_SINGLE)
        assertEquals(0xFFFFFFFFL, Group.ALL_GROUPS)
    }

    // ===========================================================================================
    // Group control — an ordinary LSEffectPayload with groupMask set
    // ===========================================================================================

    @Test
    fun testControlSingleGroupMask() {
        val payload = LSEffectPayload(
            groupMask = Group.GRP7,
            effectType = EffectType.ON,
            color = Colors.CYAN
        )
        assertEquals(Colors.CYAN, payload.color)
        assertEquals(Group.GRP7, payload.groupMask)
        assertEquals(MsgType.EFFECT, payload.msgType)
    }

    @Test
    fun testControlCombinesGrpConstantsWithOr() {
        val payload = LSEffectPayload(
            groupMask = Group.GRP1 or Group.GRP3,
            effectType = EffectType.BLINK,
            color = Colors.WHITE
        )
        assertEquals(Group.GRP1 or Group.GRP3, payload.groupMask)
        assertEquals(MsgType.EFFECT, payload.msgType)
    }

    @Test
    fun testDefaultGroupMaskIsAllSingle() {
        // groupMask omitted -> ALL_SINGLE, so a plain payload targets every connected
        // lightstick regardless of group assignment.
        val payload = LSEffectPayload(effectType = EffectType.OFF, color = Colors.WHITE)
        assertEquals(Group.ALL_SINGLE, payload.groupMask)
        assertEquals(MsgType.EFFECT, payload.msgType)
    }

    @Test
    fun testControlAllGroupsUsesMaskAllBits() {
        val payload = LSEffectPayload(
            groupMask = Group.ALL_GROUPS,
            effectType = EffectType.OFF,
            color = Colors.WHITE
        )
        assertEquals(Group.ALL_GROUPS, payload.groupMask)
    }

    @Test
    fun testControlPositionalConstructorShape() {
        // The shape the SDK is designed around: groupMask, effectType, color positionally.
        val payload = LSEffectPayload(Group.GRP1 or Group.GRP3, EffectType.ON, Colors.WHITE)
        assertEquals(Group.GRP1 or Group.GRP3, payload.groupMask)
        assertEquals(EffectType.ON, payload.effectType)
        assertEquals(Colors.WHITE, payload.color)
    }

    @Test
    fun testControlMsgTypeAndByteLayout() {
        val bytes = LSEffectPayload(effectType = EffectType.BLINK, color = Colors.WHITE).toByteArray()
        assertEquals(20, bytes.size)
        assertEquals(0, bytes[0].toInt()) // msgType = EFFECT (no dedicated GroupControl msgType)
        assertEquals(0, bytes[1].toInt()); assertEquals(0, bytes[2].toInt())
        assertEquals(0, bytes[3].toInt()); assertEquals(0, bytes[4].toInt()) // groupMask = 0 (all/single)
        assertEquals(3, bytes[11].toInt()) // effectType = BLINK
    }

    @Test
    fun testControlGroupsMaskByteLayout() {
        val bytes = LSEffectPayload(
            groupMask = Group.GRP1 or Group.GRP3,
            effectType = EffectType.ON,
            color = Colors.WHITE
        ).toByteArray()
        // groupMask (offset 1-4, u32 LE): bit0 | bit2 = 0x00000005
        assertEquals(0x05, bytes[1].toInt() and 0xFF)
        assertEquals(0, bytes[2].toInt()); assertEquals(0, bytes[3].toInt()); assertEquals(0, bytes[4].toInt())
    }

    // ===========================================================================================
    // GroupSetup wire format (mirrors Device.sendGroupSetting's field values)
    // ===========================================================================================

    @Test
    fun testGroupSetupWireFormat() {
        val payload = LSEffectPayload(
            groupMask = 1L shl (5 - 1),
            effectType = EffectType.BLINK,
            color = GroupPalette.colorFor(5),
            backgroundColor = Colors.BLACK,
            msgType = MsgType.GROUP_SETUP,
            period = 6,
            spf = 100,
            randomColor = 0,
            randomDelay = 0,
            fade = 0,
            broadcasting = 0
        )
        val bytes = payload.toByteArray()

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
        assertEquals(0, bytes[15].toInt())  // randomDelay fixed 0
        assertEquals(0, bytes[16].toInt())  // fadeValue fixed 0
        assertEquals(0, bytes[17].toInt())  // broadcasting fixed 0
    }

    // ===========================================================================================
    // Round-trip
    // ===========================================================================================

    @Test
    fun testRoundTripSetup() {
        val original = LSEffectPayload(
            groupMask = 1L shl (12 - 1),
            effectType = EffectType.BLINK,
            color = GroupPalette.colorFor(12),
            backgroundColor = Colors.BLACK,
            msgType = MsgType.GROUP_SETUP,
            period = 6,
            spf = 100,
            randomColor = 0,
            randomDelay = 0,
            fade = 0,
            broadcasting = 0
        )
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
        val original = LSEffectPayload(
            groupMask = Group.GRP2 or Group.GRP5,
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
