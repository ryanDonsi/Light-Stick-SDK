package com.lightstick

import com.lightstick.control.ControlCmd
import com.lightstick.device.FirmwareVersion
import com.lightstick.internal.ble.ControlFrame
import com.lightstick.types.Group
import com.lightstick.types.LSEffectPayload
import com.lightstick.types.MsgType
import org.junit.Assert.*
import org.junit.Test

/** BLE protocol v2.2 — Control (FF0A, layout C) and firmware version rule (§6). */
class ControlProtocolTest {

    @Test fun controlCmdCodes() {
        assertEquals(1, ControlCmd.SHOW_ENTER.cmdIndex)
        assertEquals(2, ControlCmd.SHOW_EXIT.cmdIndex)
        assertEquals(3, ControlCmd.PAUSE.cmdIndex)
        assertEquals(4, ControlCmd.RESUME.cmdIndex)
        assertEquals(5, ControlCmd.SYSTEM_OFF.cmdIndex)
    }

    @Test fun msgTypeControlCode() {
        assertEquals(3, MsgType.CONTROL.code)
        assertEquals(MsgType.CONTROL, MsgType.fromCode(3))
    }

    @Test fun unknownMsgTypeIsIgnoredNotEffect() {
        assertNull(MsgType.fromCode(99))
        assertNull(MsgType.fromCode(4))
        val f = ByteArray(20).also { it[0] = 99 }
        assertThrows(IllegalArgumentException::class.java) { LSEffectPayload.fromByteArray(f) }
    }

    @Test fun pauseResumeCodesMatchFrameConstants() {
        assertEquals(ControlCmd.PAUSE.cmdIndex, ControlFrame.CMD_PAUSE)
        assertEquals(ControlCmd.RESUME.cmdIndex, ControlFrame.CMD_RESUME)
    }

    @Test fun showEnterFrameLayout() {
        val f = ControlFrame.build(ControlCmd.SHOW_ENTER.cmdIndex, 0L, 7)
        assertEquals(20, f.size)
        val expected = ByteArray(20).also { it[0] = 3; it[5] = 1; it[17] = 7 }
        assertArrayEquals(expected, f)
    }

    @Test fun groupMaskIsLittleEndianAtOffset1() {
        val f = ControlFrame.build(5, Group.GRP1 or Group.GRP3 or Group.GRP32, 1)
        assertEquals(0x05, f[1].toInt() and 0xFF)
        assertEquals(0x00, f[2].toInt() and 0xFF)
        assertEquals(0x00, f[3].toInt() and 0xFF)
        assertEquals(0x80, f[4].toInt() and 0xFF)
        assertEquals(5, f[5].toInt())
        val all = ControlFrame.build(3, Group.ALL_GROUPS, 1)
        for (i in 1..4) assertEquals(0xFF, all[i].toInt() and 0xFF)
        for (i in 6..16) assertEquals(0, f[i].toInt())
        assertEquals(0, f[18].toInt()); assertEquals(0, f[19].toInt())
    }

    @Test fun frameValidation() {
        assertThrows(IllegalArgumentException::class.java) { ControlFrame.build(1, -1L, 0) }
        assertThrows(IllegalArgumentException::class.java) { ControlFrame.build(1, 0x1_0000_0000L, 0) }
        assertThrows(IllegalArgumentException::class.java) { ControlFrame.build(1, 0L, 256) }
    }

    @Test fun effectIndexCyclesSkippingZero() {
        assertEquals(1, ControlFrame.nextEffectIndex(0))
        assertEquals(2, ControlFrame.nextEffectIndex(1))
        assertEquals(255, ControlFrame.nextEffectIndex(254))
        assertEquals(1, ControlFrame.nextEffectIndex(255))
    }

    @Test fun effectPayloadRejectsControlFrame() {
        val f = ControlFrame.build(1, 0L, 1)
        assertThrows(IllegalArgumentException::class.java) { LSEffectPayload.fromByteArray(f) }
    }

    @Test fun firmwareVersionOrdering() {
        val v = { s: String -> FirmwareVersion.parse(s)!! }
        assertTrue(v("1.0.7") < v("2.200"))
        assertTrue(v("2.200") < v("2.201"))
        assertTrue(v("2.201") < v("2.300"))
        assertTrue(v("2.999") < v("3.000"))
    }

    @Test fun protocolSupport() {
        assertTrue(FirmwareVersion.parse("2.200")!!.supportsProtocol(2, 2))
        assertTrue(FirmwareVersion.parse("2.203")!!.supportsProtocol(2, 2))
        assertTrue(FirmwareVersion.parse("2.300")!!.supportsProtocol(2, 2))
        assertFalse(FirmwareVersion.parse("2.150")!!.supportsProtocol(2, 2))
        assertFalse(FirmwareVersion.parse("1.0.7")!!.supportsProtocol(2, 2))
        assertTrue(FirmwareVersion.PROTOCOL_2_2.supportsProtocol(2, 2))
    }

    @Test fun firmwareParsing() {
        assertEquals(FirmwareVersion(2, 201), FirmwareVersion.parse(" 2.201\u0000"))
        assertEquals(FirmwareVersion(1, 0), FirmwareVersion.parse("v1.0.7"))
        assertNull(FirmwareVersion.parse("unknown"))
        assertNull(FirmwareVersion.parse(null))
        assertEquals("2.200", FirmwareVersion(2, 200).toString())
        assertEquals(2, FirmwareVersion(2, 201).protocolMinor)
    }
}
