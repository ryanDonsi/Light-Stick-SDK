package com.lightstick.ota

/**
 * Represents the progress state of an OTA (Over-the-Air) firmware update.
 *
 * Progress is always expressed as a percentage value in the range **0–100**.
 * The SDK calls this class from [OtaManager.start] via the `onProgress` callback.
 *
 * Example usage:
 * ```kotlin
 * OtaManager.start(firmwareBytes,
 *     onProgress = { p -> println("OTA progress: ${p.percent}%") },
 *     onResult = { r -> println("OTA result: ${r.success}") }
 * )
 * ```
 *
 * @param percent The current OTA progress percentage (0–100 inclusive).
 * @throws IllegalArgumentException If [percent] is outside the valid range.
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.OtaSamples.sampleOtaProgress
 */
data class OtaProgress(val percent: Int) {
    init {
        require(percent in 0..100) { "percent must be within 0..100" }
    }
}
