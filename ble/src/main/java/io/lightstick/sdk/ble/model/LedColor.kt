package io.lightstick.sdk.ble.model

/**
 * Represents an RGB color for controlling LED colors over BLE.
 *
 * This inline value class stores the red, green, and blue components as unsigned bytes,
 * and is used for efficient transmission to BLE devices.
 *
 * You can create a new color using [LedColor.invoke] or use one of the predefined colors in [companion object].
 *
 * @property red Red component (0–255)
 * @property green Green component (0–255)
 * @property blue Blue component (0–255)
 */
@JvmInline
value class LedColor private constructor(val rgb: Triple<UByte, UByte, UByte>) {

    /** Red channel (0–255) */
    val red: UByte get() = rgb.first

    /** Green channel (0–255) */
    val green: UByte get() = rgb.second

    /** Blue channel (0–255) */
    val blue: UByte get() = rgb.third

    companion object {
        /** Predefined black color (0, 0, 0) */
        val BLACK = LedColor(0, 0, 0)

        /** Predefined white color (255, 255, 255) */
        val WHITE = LedColor(255, 255, 255)

        /** Predefined red color (255, 0, 0) */
        val RED = LedColor(255, 0, 0)

        /** Predefined green color (0, 255, 0) */
        val GREEN = LedColor(0, 255, 0)

        /** Predefined blue color (0, 0, 255) */
        val BLUE = LedColor(0, 0, 255)

        /** Predefined yellow color (255, 255, 0) */
        val YELLOW = LedColor(255, 255, 0)

        /** Predefined cyan color (0, 255, 255) */
        val CYAN = LedColor(0, 255, 255)

        /** Predefined magenta color (255, 0, 255) */
        val MAGENTA = LedColor(255, 0, 255)

        /** Predefined orange color (255, 165, 0) */
        val ORANGE = LedColor(255, 165, 0)

        /** Predefined purple color (128, 0, 128) */
        val PURPLE = LedColor(128, 0, 128)

        /**
         * Factory method to create a [LedColor] instance from RGB components.
         *
         * @param red Red value (0–255)
         * @param green Green value (0–255)
         * @param blue Blue value (0–255)
         * @return [LedColor] instance representing the given RGB color
         *
         * @sample
         * val color = LedColor(128, 64, 255)
         */
        operator fun invoke(red: Int, green: Int, blue: Int): LedColor =
            LedColor(Triple(red.toUByte(), green.toUByte(), blue.toUByte()))
    }
}
