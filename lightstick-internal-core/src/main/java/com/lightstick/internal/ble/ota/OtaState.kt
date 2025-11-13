package com.lightstick.internal.ble.ota

/** Lightweight OTA phase model for logging/UI. */
internal enum class OtaState {
    IDLE, PREPARE, TRANSFER, VERIFY, COMPLETE, ERROR, ABORTED
}
