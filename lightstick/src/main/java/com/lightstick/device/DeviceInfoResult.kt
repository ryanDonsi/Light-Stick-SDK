package com.lightstick.device

/**
 * Result type for [com.lightstick.LSBluetooth.getCachedDeviceInfo].
 *
 * Use [Available] to access device info, or inspect [Error.code] to determine
 * why the info is not currently available.
 */
sealed class DeviceInfoResult {

    /** Device info is available and populated. */
    data class Available(val info: DeviceInfo) : DeviceInfoResult()

    /** Device info is not currently available. */
    data class Error(val code: ErrorCode) : DeviceInfoResult()

    enum class ErrorCode {
        /** The device is not connected. */
        NOT_CONNECTED,

        /**
         * The device is connected but DIS has not been read yet.
         * This should not occur under normal conditions because the SDK reads DIS
         * automatically before delivering the [com.lightstick.device.Device.connect]
         * onConnected callback.
         */
        INFO_NOT_READY,
    }
}
