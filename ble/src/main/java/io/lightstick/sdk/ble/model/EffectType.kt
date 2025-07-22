package io.lightstick.sdk.ble.model

/**
 * Represents different LED lighting effects that can be applied via BLE.
 *
 * Each effect corresponds to a predefined pattern supported by the device firmware.
 * These values are mapped to BLE payloads and must match the expected protocol format.
 *
 * @property value Unsigned byte representation used in BLE protocol.
 */
enum class EffectType(val value: UByte) {

    /**
     * Turns off the LED effect (idle/off state).
     */
    OFF(0u),

    /**
     * Solid color with no animation.
     */
    ON(1u),

    /**
     * Blinking effect (strobe), typically alternating on/off rapidly.
     */
    STROBE(2u),

    /**
     * Smooth fade-in and fade-out animation (breathing effect).
     */
    BREATH(3u);

    companion object {
        /**
         * Retrieves the [EffectType] that corresponds to the given integer value.
         * If the value does not match any known type, defaults to [OFF].
         *
         * @param value Integer value received from user input or deserialized data
         * @return Corresponding [EffectType] enum value
         *
         * @sample
         * val type = EffectType.from(2) // returns EffectType.STROBE
         * val unknown = EffectType.from(99) // returns EffectType.OFF
         */
        fun from(value: Int): EffectType =
            entries.firstOrNull { it.value.toInt() == value } ?: OFF
    }
}

