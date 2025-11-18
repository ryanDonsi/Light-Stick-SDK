package com.lightstick.test

import com.lightstick.types.Color
import com.lightstick.types.Colors
import com.lightstick.types.EffectType
import com.lightstick.types.LSEffectPayload
import org.junit.Assert.*
import org.junit.Test

/**
 * Light Stick SDK 기본 단위 테스트
 *
 * 가장 기본적인 기능들만 테스트하는 간단한 예제입니다.
 */
class BasicLightStickTest {

    @Test
    fun testColorCreation() {
        // 빨간색 생성
        val red = Color(255, 0, 0)
        assertEquals(255, red.r)
        assertEquals(0, red.g)
        assertEquals(0, red.b)
    }

    @Test
    fun testPredefinedColors() {
        // 미리 정의된 색상 사용
        assertNotNull(Colors.RED)
        assertNotNull(Colors.GREEN)
        assertNotNull(Colors.BLUE)
    }

    @Test
    fun testColorToRgbBytes() {
        // 색상을 RGB 바이트 배열로 변환
        val blue = Colors.BLUE
        val rgbBytes = blue.toRgbBytes()

        // RGB 바이트는 3바이트여야 함 [R, G, B]
        assertEquals(3, rgbBytes.size)
        assertEquals(0, rgbBytes[0].toInt())     // R
        assertEquals(0, rgbBytes[1].toInt())     // G
        assertEquals(255.toByte(), rgbBytes[2])  // B
    }

    @Test
    fun testEffectTypes() {
        // 이펙트 타입 코드 확인
        assertEquals(0, EffectType.OFF.code)
        assertEquals(1, EffectType.ON.code)
        assertEquals(2, EffectType.STROBE.code)
        assertEquals(3, EffectType.BLINK.code)
        assertEquals(4, EffectType.BREATH.code)
    }

    @Test
    fun testBlinkEffect() {
        // Blink 이펙트 생성
        val blink = LSEffectPayload.Effects.blink(
            color = Colors.RED,
            period = 10
        )

        // 이펙트 페이로드를 바이트 배열로 변환하면 16바이트여야 함
        val bytes = blink.toByteArray()
        assertEquals(16, bytes.size)
    }

    @Test
    fun testStrobeEffect() {
        // Strobe 이펙트 생성
        val strobe = LSEffectPayload.Effects.strobe(
            color = Colors.GREEN,
            period = 5
        )

        val bytes = strobe.toByteArray()
        assertEquals(16, bytes.size)
    }

    @Test
    fun testBreathEffect() {
        // Breath 이펙트 생성
        val breath = LSEffectPayload.Effects.breath(
            color = Colors.BLUE,
            period = 20
        )

        val bytes = breath.toByteArray()
        assertEquals(16, bytes.size)
    }

    @Test
    fun testOnEffect() {
        // ON 이펙트 (상시 점등)
        val on = LSEffectPayload.Effects.on(Colors.WHITE)

        val bytes = on.toByteArray()
        assertEquals(16, bytes.size)
    }

    @Test
    fun testOffEffect() {
        // OFF 이펙트 (소등)
        val off = LSEffectPayload.Effects.off()

        val bytes = off.toByteArray()
        assertEquals(16, bytes.size)
    }
}