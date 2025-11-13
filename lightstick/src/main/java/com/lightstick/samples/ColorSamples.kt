package com.lightstick.samples

import com.lightstick.types.Color
import com.lightstick.types.Colors

/**
 * Color type usage samples.
 */
object ColorSamples {

    fun sampleColorBasics() {
        val red = Color(255, 0, 0)
        val green = Color(0, 255, 0)
        val blue = Color(0, 0, 255)
        println(red); println(green); println(blue)
    }

    fun sampleToRgbBytes() {
        val c = Color(10, 20, 30)
        val bytes = c.toRgbBytes()
        println(bytes.joinToString(prefix = "[", postfix = "]") { (it.toInt() and 0xFF).toString() })
    }

    fun samplePresetColors() {
        println(Colors.WHITE)
        println(Colors.ORANGE)
        println(Colors.PURPLE)
    }
}
