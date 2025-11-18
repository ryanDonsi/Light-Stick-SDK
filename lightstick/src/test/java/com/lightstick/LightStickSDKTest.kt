package com.lightstick.test

import com.lightstick.types.Color
import com.lightstick.types.Colors
import com.lightstick.types.EffectType
import com.lightstick.types.LSEffectPayload
import org.junit.Assert.*
import org.junit.Test

/**
 * Light Stick SDK 단위 테스트 예제
 *
 * BLE 연결이 필요 없는 SDK 기본 기능들을 테스트합니다.
 */
class LightStickSDKTest {

    // ===========================================================================================
    // Color 테스트
    // ===========================================================================================

    @Test
    fun testColorCreation() {
        // RGB 값으로 Color 생성
        val red = Color(255, 0, 0)
        assertEquals(255, red.r)
        assertEquals(0, red.g)
        assertEquals(0, red.b)
    }

    @Test
    fun testColorToRgbBytes() {
        // Color를 3바이트 RGB 배열로 변환 테스트
        val blue = Colors.BLUE
        val rgbBytes = blue.toRgbBytes()

        assertEquals(3, rgbBytes.size)
        assertEquals(0, rgbBytes[0].toInt())     // R
        assertEquals(0, rgbBytes[1].toInt())     // G
        assertEquals(255.toByte(), rgbBytes[2])  // B
    }

    @Test
    fun testPredefinedColors() {
        // 미리 정의된 색상 테스트
        assertEquals(255, Colors.RED.r)
        assertEquals(0, Colors.RED.g)
        assertEquals(0, Colors.RED.b)

        assertEquals(0, Colors.GREEN.r)
        assertEquals(255, Colors.GREEN.g)
        assertEquals(0, Colors.GREEN.b)

        assertEquals(0, Colors.BLUE.r)
        assertEquals(0, Colors.BLUE.g)
        assertEquals(255, Colors.BLUE.b)
    }

    // ===========================================================================================
    // EffectType 테스트
    // ===========================================================================================

    @Test
    fun testEffectTypeFromCode() {
        // 코드로부터 EffectType 생성 테스트
        assertEquals(EffectType.OFF, EffectType.fromCode(0))
        assertEquals(EffectType.ON, EffectType.fromCode(1))
        assertEquals(EffectType.STROBE, EffectType.fromCode(2))
        assertEquals(EffectType.BLINK, EffectType.fromCode(3))
        assertEquals(EffectType.BREATH, EffectType.fromCode(4))
    }

    @Test
    fun testEffectTypeInvalidCode() {
        // 잘못된 코드는 OFF로 반환
        assertEquals(EffectType.OFF, EffectType.fromCode(99))
        assertEquals(EffectType.OFF, EffectType.fromCode(-1))
    }

    @Test
    fun testEffectTypeCodes() {
        // EffectType의 code 값 테스트
        assertEquals(0, EffectType.OFF.code)
        assertEquals(1, EffectType.ON.code)
        assertEquals(2, EffectType.STROBE.code)
        assertEquals(3, EffectType.BLINK.code)
        assertEquals(4, EffectType.BREATH.code)
    }

    // ===========================================================================================
    // LSEffectPayload 테스트
    // ===========================================================================================

    @Test
    fun testBlinkEffect() {
        // Blink 이펙트 생성 및 검증
        val blinkPayload = LSEffectPayload.Effects.blink(
            color = Colors.RED,
            period = 10
        )

        val bytes = blinkPayload.toByteArray()
        assertEquals(16, bytes.size)
        // 이펙트 타입 확인 (BLINK = 3)
        assertEquals(3, bytes[7].toInt())
    }

    @Test
    fun testStrobeEffect() {
        // Strobe 이펙트 생성 및 검증
        val strobePayload = LSEffectPayload.Effects.strobe(
            color = Colors.GREEN,
            period = 5
        )

        val bytes = strobePayload.toByteArray()
        assertEquals(16, bytes.size)
        // 이펙트 타입 확인 (STROBE = 2)
        assertEquals(2, bytes[7].toInt())
    }

    @Test
    fun testBreathEffect() {
        // Breath 이펙트 생성 및 검증
        val breathPayload = LSEffectPayload.Effects.breath(
            color = Colors.BLUE,
            period = 20
        )

        val bytes = breathPayload.toByteArray()
        assertEquals(16, bytes.size)
        // 이펙트 타입 확인 (BREATH = 4)
        assertEquals(4, bytes[7].toInt())
    }

    @Test
    fun testOnEffect() {
        // ON 이펙트 생성 및 검증
        val onPayload = LSEffectPayload.Effects.on(Colors.CYAN)

        val bytes = onPayload.toByteArray()
        assertEquals(16, bytes.size)
        // 이펙트 타입 확인 (ON = 1)
        assertEquals(1, bytes[7].toInt())
    }

    @Test
    fun testOffEffect() {
        // OFF 이펙트 생성 및 검증
        val offPayload = LSEffectPayload.Effects.off()

        val bytes = offPayload.toByteArray()
        assertEquals(16, bytes.size)
        // 이펙트 타입 확인 (OFF = 0)
        assertEquals(0, bytes[7].toInt())
    }

    // ===========================================================================================
    // 통합 시나리오 테스트
    // ===========================================================================================

    @Test
    fun testColorTransitionScenario() {
        // 여러 색상으로 전환하는 시나리오
        val colors = listOf(
            Colors.RED,
            Colors.GREEN,
            Colors.BLUE,
            Colors.YELLOW,
            Colors.MAGENTA,
            Colors.CYAN
        )

        colors.forEach { color ->
            val rgbBytes = color.toRgbBytes()
            assertEquals(3, rgbBytes.size)
            // RGB 값들이 유효한 범위인지 확인
            assertTrue(rgbBytes[0].toInt() and 0xFF in 0..255)
            assertTrue(rgbBytes[1].toInt() and 0xFF in 0..255)
            assertTrue(rgbBytes[2].toInt() and 0xFF in 0..255)
        }
    }

    @Test
    fun testEffectSequenceScenario() {
        // 다양한 이펙트 시퀀스 생성
        val effects = listOf(
            LSEffectPayload.Effects.on(Colors.RED),
            LSEffectPayload.Effects.blink(Colors.GREEN, period = 5),
            LSEffectPayload.Effects.strobe(Colors.BLUE, period = 3),
            LSEffectPayload.Effects.breath(Colors.YELLOW, period = 15),
            LSEffectPayload.Effects.off()
        )

        // 모든 이펙트가 16바이트인지 확인
        effects.forEach { payload ->
            val bytes = payload.toByteArray()
            assertEquals(16, bytes.size)
        }
    }

    @Test
    fun testCustomColorCreation() {
        // 커스텀 색상 생성 테스트
        val customColors = listOf(
            Color(128, 64, 32),   // 갈색 계열
            Color(255, 192, 203), // 핑크
            Color(75, 0, 130),    // 인디고
            Color(255, 165, 0)    // 오렌지
        )

        customColors.forEach { color ->
            assertTrue(color.r in 0..255)
            assertTrue(color.g in 0..255)
            assertTrue(color.b in 0..255)
        }
    }

    @Test
    fun testEffectWithDifferentPeriods() {
        // 다양한 period 값으로 이펙트 생성
        val periods = listOf(1, 5, 10, 20, 30, 60)

        periods.forEach { period ->
            val blink = LSEffectPayload.Effects.blink(Colors.WHITE, period)
            assertEquals(16, blink.toByteArray().size)

            val breath = LSEffectPayload.Effects.breath(Colors.WHITE, period)
            assertEquals(16, breath.toByteArray().size)
        }
    }

    // ===========================================================================================
    // 경계값 테스트
    // ===========================================================================================

    @Test
    fun testColorBoundaryValues() {
        // RGB 경계값 테스트
        val maxColor = Color(255, 255, 255)
        assertEquals(255, maxColor.r)
        assertEquals(255, maxColor.g)
        assertEquals(255, maxColor.b)

        val minColor = Color(0, 0, 0)
        assertEquals(0, minColor.r)
        assertEquals(0, minColor.g)
        assertEquals(0, minColor.b)
    }

    @Test
    fun testRgbBytesBoundaryValues() {
        // RGB 바이트 변환 경계값 테스트
        val maxColor = Color(255, 255, 255)
        val maxBytes = maxColor.toRgbBytes()
        assertEquals(255.toByte(), maxBytes[0])
        assertEquals(255.toByte(), maxBytes[1])
        assertEquals(255.toByte(), maxBytes[2])

        val minColor = Color(0, 0, 0)
        val minBytes = minColor.toRgbBytes()
        assertEquals(0, minBytes[0].toInt())
        assertEquals(0, minBytes[1].toInt())
        assertEquals(0, minBytes[2].toInt())
    }

    @Test
    fun testPeriodBoundaryValues() {
        // period 경계값 테스트
        val minPeriod = LSEffectPayload.Effects.blink(Colors.RED, period = 1)
        assertEquals(16, minPeriod.toByteArray().size)

        val maxPeriod = LSEffectPayload.Effects.blink(Colors.RED, period = 255)
        assertEquals(16, maxPeriod.toByteArray().size)
    }
}