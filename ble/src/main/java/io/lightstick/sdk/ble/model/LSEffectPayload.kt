package io.lightstick.sdk.ble.model

/**
 * Represents a structured payload to control LED effects over BLE.
 *
 * This payload is used by the LightStick GATT protocol to define complex LED behaviors,
 * including strobe, breath, and synchronized effects.
 *
 * The [toByteArray] function generates a 15-byte array that can be sent directly to
 * the BLE device via the LED_EFFECT_PAYLOAD characteristic.
 *
 * @property ledMask Bitmask for selecting which LEDs to affect (0x0000 = all LEDs).
 *                   Each bit from LSB to MSB corresponds to LED index 0–15.
 * @property color RGB color to be used in the effect.
 * @property effectType Type of LED effect (e.g., ON, STROBE, BREATH).
 * @property durationMs Duration of the effect in milliseconds.
 * @property period Time between effect repeats (0–255ms).
 * @property spf Speed factor or frame interval (in ms).
 * @property randomColor Flag to enable random color per frame (0 = off, 1 = on).
 * @property randomDelay Flag to randomize delay per LED or frame.
 * @property timestampMs Time sync offset in milliseconds.
 * @property syncIndex Global sync group index (used to synchronize multiple devices).
 *
 * @sample
 * ```kotlin
 * val payload = LSEffectPayload(
 *     ledMask = 0xFFFFu,
 *     color = LedColor.BLUE,
 *     effectType = EffectType.STROBE,
 *     durationMs = 1000u,
 *     period = 50u,
 *     spf = 20u,
 *     randomColor = 0u,
 *     randomDelay = 0u,
 *     timestampMs = 0u,
 *     syncIndex = 1
 * )
 *
 * val bytes = payload.toByteArray()
 * // Now send [bytes] to the LED_EFFECT_PAYLOAD_CHAR characteristic
 * ```
 */
data class LSEffectPayload(
    val ledMask: UShort = 0x0000u,
    val color: LedColor = LedColor.WHITE,
    val effectType: EffectType = EffectType.ON,
    val durationMs: UShort = 0u,
    val period: UByte = 0u,
    val spf: UByte = 100u,
    val randomColor: UByte = 0u,
    val randomDelay: UByte = 0u,
    val fade: UByte = 100u,
    val timestampMs: UShort = 0u,
    val syncIndex: Byte = 0
) {

    /**
     * Converts the [LSEffectPayload] into a 16-byte array in the order expected by the BLE device.
     *
     * Format:
     * ```
     * [0-1]   ledMask (LSB, MSB)
     * [2-4]   color (R, G, B)
     * [5]     effectType (enum)
     * [6-7]   durationMs (LSB, MSB)
     * [8]     period
     * [9]     spf
     * [10]    randomColor
     * [11]    randomDelay
     * [12]    fade
     * [13-14] timestampMs (LSB, MSB)
     * [15]    syncIndex
     * ```
     *
     * @return ByteArray containing the packed effect payload (16 bytes).
     */
    fun toByteArray(): ByteArray = byteArrayOf(
        (ledMask.toInt() and 0xFF).toByte(),
        (ledMask.toInt() shr 8).toByte(),
        color.red.toByte(),
        color.green.toByte(),
        color.blue.toByte(),
        effectType.value.toByte(),
        (durationMs.toInt() and 0xFF).toByte(),
        (durationMs.toInt() shr 8).toByte(),
        period.toByte(),
        spf.toByte(),
        randomColor.toByte(),
        randomDelay.toByte(),
        fade.toByte(),
        (timestampMs.toInt() and 0xFF).toByte(),
        (timestampMs.toInt() shr 8).toByte(),
        syncIndex
    )
}