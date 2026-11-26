package com.lightstick.internal.ble.state

import androidx.annotation.RestrictTo

/**
 * Internal representation of BLE disconnection reasons.
 *
 * Maps GATT status codes to semantic disconnect reasons for internal state management.
 * These are internal types and should not be exposed directly to the public API.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
enum class InternalDisconnectReason {
    /** User explicitly requested disconnection */
    USER_REQUESTED,

    /** Device powered off or battery depleted */
    DEVICE_POWERED_OFF,

    /** Connection timeout occurred */
    TIMEOUT,

    /** Device moved out of range */
    OUT_OF_RANGE,

    /** GATT error occurred (status 133) */
    GATT_ERROR,

    /** Unknown or unspecified reason */
    UNKNOWN
}