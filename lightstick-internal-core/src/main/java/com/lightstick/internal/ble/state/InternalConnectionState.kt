package com.lightstick.internal.ble.state

/**
 * Internal representation of BLE connection state.
 *
 * This sealed class hierarchy represents the connection lifecycle for internal state management.
 * These types are NOT exposed to the public API - they are converted to public types at the
 * API boundary layer.
 *
 * Design notes:
 * - Sealed class for exhaustive when() checking
 * - Immutable data classes for thread safety
 * - Internal visibility to prevent module boundary violations
 */
sealed class InternalConnectionState {

    /**
     * Device is disconnected.
     *
     * @property reason The reason for disconnection (if known)
     * @property timestamp When the disconnection occurred (system time in milliseconds)
     */
    data class Disconnected(
        val reason: InternalDisconnectReason = InternalDisconnectReason.UNKNOWN,
        val timestamp: Long = System.currentTimeMillis()
    ) : InternalConnectionState()

    /**
     * Connection attempt is in progress.
     *
     * @property timestamp When the connection attempt started
     */
    data class Connecting(
        val timestamp: Long = System.currentTimeMillis()
    ) : InternalConnectionState()

    /**
     * Device is connected and ready for communication.
     *
     * @property timestamp When the connection was established
     * @property mtu Negotiated MTU size (null if not yet negotiated)
     */
    data class Connected(
        val timestamp: Long = System.currentTimeMillis(),
        val mtu: Int? = null
    ) : InternalConnectionState()

    /**
     * Disconnection is in progress.
     *
     * @property timestamp When the disconnection started
     */
    data class Disconnecting(
        val timestamp: Long = System.currentTimeMillis()
    ) : InternalConnectionState()
}