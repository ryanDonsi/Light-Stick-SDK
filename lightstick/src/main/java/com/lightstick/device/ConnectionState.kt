package com.lightstick.device

/**
 * Public API representation of BLE connection state.
 *
 * This sealed class hierarchy provides a clean, type-safe way to observe
 * connection lifecycle events in the public API.
 *
 * Example usage:
 * ```
 * LSBluetooth.observeConnectionStates().collect { states ->
 *     states.forEach { (mac, state) ->
 *         when (state) {
 *             is ConnectionState.Connected -> println("$mac connected")
 *             is ConnectionState.Disconnected -> println("$mac disconnected: ${state.reason}")
 *             is ConnectionState.Connecting -> println("$mac connecting...")
 *             is ConnectionState.Disconnecting -> println("$mac disconnecting...")
 *         }
 *     }
 * }
 * ```
 *
 * @since 1.0.0
 */
sealed class ConnectionState {

    /**
     * Reason for disconnection.
     */
    enum class DisconnectReason {
        /** User explicitly requested disconnection */
        USER_REQUESTED,

        /** Device powered off or battery depleted */
        DEVICE_POWERED_OFF,

        /** Connection timeout occurred */
        TIMEOUT,

        /** Device moved out of range */
        OUT_OF_RANGE,

        /** GATT error occurred */
        GATT_ERROR,

        /** Unknown or unspecified reason */
        UNKNOWN
    }

    /**
     * Device is disconnected.
     *
     * @property reason The reason for disconnection
     * @property timestamp When the disconnection occurred (system time in milliseconds)
     */
    data class Disconnected(
        val reason: DisconnectReason = DisconnectReason.UNKNOWN,
        val timestamp: Long = System.currentTimeMillis()
    ) : ConnectionState()

    /**
     * Connection attempt is in progress.
     *
     * @property timestamp When the connection attempt started
     */
    data class Connecting(
        val timestamp: Long = System.currentTimeMillis()
    ) : ConnectionState()

    /**
     * Device is connected and ready for communication.
     *
     * @property timestamp When the connection was established
     * @property mtu Negotiated MTU size (null if not yet negotiated)
     */
    data class Connected(
        val timestamp: Long = System.currentTimeMillis(),
        val mtu: Int? = null
    ) : ConnectionState()

    /**
     * Disconnection is in progress.
     *
     * @property timestamp When the disconnection started
     */
    data class Disconnecting(
        val timestamp: Long = System.currentTimeMillis()
    ) : ConnectionState()
}