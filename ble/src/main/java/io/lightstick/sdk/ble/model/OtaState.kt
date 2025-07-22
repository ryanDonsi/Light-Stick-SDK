package io.lightstick.sdk.ble.model

/**
 * Represents the current OTA (Over-the-Air) update state of a BLE device.
 *
 * This enum is used to track and communicate the progress and result of
 * an OTA firmware update process via BLE.
 */
enum class OtaState {

    /**
     * The OTA process has not started yet.
     * - This is the default state before any OTA operation begins.
     * - Also used to reset the state after a completed or failed update.
     */
    Idle,

    /**
     * The OTA process is currently in progress.
     * - Firmware chunks are being sent sequentially to the BLE device.
     * - Progress percentage is typically updated during this state.
     * - Any user UI showing OTA progress bars can be bound to this state.
     */
    InProgress,

    /**
     * The OTA process completed successfully.
     * - The device acknowledged all OTA packets and sent a success result.
     * - Typically transitions from `InProgress` to `Completed`.
     * - After this state, the device may reset or reboot depending on implementation.
     */
    Completed,

    /**
     * The OTA process failed or was aborted.
     * - Can result from device disconnection, invalid response, or OTA timeout.
     * - May also be triggered by receiving a failure result (e.g., 0xFF06 with failure code).
     * - Typically requires user to retry the OTA process.
     */
    Failed
}
