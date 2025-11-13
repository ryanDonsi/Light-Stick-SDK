package com.lightstick.ota

/**
 * Represents the final outcome of an OTA (Over-the-Air) firmware update.
 *
 * This class is used in the `onResult` callback of [OtaManager.start] to indicate
 * whether the OTA process succeeded or failed, along with an optional message
 * describing the reason or additional details.
 *
 * Example usage:
 * ```kotlin
 * OtaManager.start(firmwareBytes,
 *     onProgress = { p -> println("Progress: ${p.percent}%") },
 *     onResult = { r ->
 *         if (r.success) println("OTA succeeded!")
 *         else println("OTA failed: ${r.message}")
 *     }
 * )
 * ```
 *
 * @param success Indicates whether the OTA completed successfully.
 * @param message Optional message providing additional information (e.g., error cause).
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.OtaSamples.sampleOtaResult
 */
data class OtaResult(
    val success: Boolean,
    val message: String? = null
)
