package com.lightstick.types

/**
 * Immutable RGB color representation with each component in the inclusive range [0, 255].
 *
 * This class is used across the SDK to represent logical LED colors. When writing to
 * a device, the RGB components are usually combined with a transition byte into a
 * 4-byte packet `[R, G, B, transition]`.
 *
 * Validation:
 * - Throws [IllegalArgumentException] if any component is outside [0, 255].
 *
 * Example usage:
 * ```kotlin
 * val red = Color(255, 0, 0)
 * val packet = red.toRgbBytes() + byteArrayOf(10) // [R,G,B,transition]
 * ```
 *
 * @param r Red component (0–255 inclusive)
 * @param g Green component (0–255 inclusive)
 * @param b Blue component (0–255 inclusive)
 * @throws IllegalArgumentException If any of r, g, or b is outside [0, 255].
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.ColorSamples.sampleColorBasics
 */
data class Color(
    val r: Int,
    val g: Int,
    val b: Int
) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) {
            "RGB components must be within 0..255"
        }
    }

    /**
     * Converts this color to a raw 3-byte `[R, G, B]` array.
     *
     * This helper is useful for constructing custom BLE payloads or composing
     * LED packets manually.
     *
     * @return A [ByteArray] of length 3 containing `[r, g, b]` in order.
     * @sample com.lightstick.samples.ColorSamples.sampleToRgbBytes
     */
    fun toRgbBytes(): ByteArray = byteArrayOf(r.toByte(), g.toByte(), b.toByte())
}
