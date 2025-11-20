package com.lightstick.types

/**
 * Defines the LED effect types supported by the LightStick firmware (EFX v1.4).
 *
 * Each type corresponds to a numeric code stored in the 20-byte effect payload sent
 * over BLE. These codes must match the firmware protocol definition exactly.
 *
 * Mapping table:
 * | Code | Effect    | Description                |
 * |------|------------|----------------------------|
 * | 0    | OFF        | LEDs off                   |
 * | 1    | ON         | Constant steady light      |
 * | 2    | STROBE     | Rapid on/off flashes       |
 * | 3    | BLINK      | Slow periodic blinking     |
 * | 4    | BREATH     | Smooth fade in/out effect  |
 *
 * Use [fromCode] to safely resolve an enum from a firmware value received via BLE
 * or from persisted `.efx` data.
 *
 * Example usage:
 * ```kotlin
 * val effect = EffectType.fromCode(3)
 * println(effect) // BLINK
 * ```
 *
 * @property code The numeric firmware code representing this effect.
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.EffectSamples.sampleEffectTypeFromCode
 */
enum class EffectType(val code: Int) {

    /** LEDs off. */ OFF(0),

    /** Constant on (steady light). */ ON(1),

    /** Strobe (rapid short pulses). */ STROBE(2),

    /** Blink (periodic on/off). */ BLINK(3),

    /** Breathing (smooth fade in/out). */ BREATH(4);

    companion object {

        /**
         * Resolves an [EffectType] by its firmware [code].
         *
         * Returns [OFF] if the code does not match any known value, ensuring that
         * the SDK never triggers undefined LED behavior.
         *
         * @param code The numeric code defined by the firmware.
         * @return The corresponding [EffectType], or [OFF] if unknown.
         * @sample com.lightstick.samples.EffectSamples.sampleEffectTypeFromCode
         */
        @JvmStatic
        fun fromCode(code: Int): EffectType =
            entries.firstOrNull { it.code == code } ?: OFF
    }
}
