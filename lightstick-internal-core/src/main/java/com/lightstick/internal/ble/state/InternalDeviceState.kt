package com.lightstick.internal.ble.state

/**
 * Internal unified state container for a single BLE device.
 *
 * This data class combines connection state and device information into a single
 * cohesive state object for internal state management. It serves as the canonical
 * state representation within the internal module.
 *
 * Design principles:
 * - Single source of truth for device state
 * - Immutable for thread safety
 * - Internal visibility to prevent direct public API exposure
 *
 * @property macAddress Unique device identifier (MAC address)
 * @property connectionState Current connection lifecycle state
 * @property deviceInfo Device information (DIS, BAS, etc.), null if not yet read
 * @property lastSeenTimestamp Last time this device was observed (scan or connection event)
 */
data class InternalDeviceState(
    val macAddress: String,
    val connectionState: InternalConnectionState,
    val deviceInfo: InternalDeviceInfo? = null,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)