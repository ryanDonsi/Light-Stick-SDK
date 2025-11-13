package com.lightstick.samples

import com.lightstick.types.EffectType

/**
 * EffectType usage samples.
 */
object EffectSamples {

    fun sampleEffectTypeFromCode() {
        println(EffectType.fromCode(0)) // OFF
        println(EffectType.fromCode(3)) // BLINK
        println(EffectType.fromCode(9)) // unknown -> OFF
    }
}
