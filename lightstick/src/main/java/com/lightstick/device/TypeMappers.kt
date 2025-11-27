package com.lightstick.device

import com.lightstick.device.DeviceInfo
import com.lightstick.internal.ble.state.InternalConnectionState
import com.lightstick.internal.ble.state.InternalDeviceInfo
import com.lightstick.internal.ble.state.InternalDeviceState
import com.lightstick.internal.ble.state.InternalDisconnectReason

/**
 * Type conversion utilities for mapping internal state types to public API types.
 *
 * This object is internal to the SDK and not exposed in the public API surface.
 * It handles the conversion between internal module types and public module types
 * at the API boundary.
 */
internal object TypeMappers {

    // ============================================================================================
    // DisconnectReason
    // ============================================================================================

    fun toPublic(reason: InternalDisconnectReason): ConnectionState.DisconnectReason {
        return when (reason) {
            InternalDisconnectReason.USER_REQUESTED -> ConnectionState.DisconnectReason.USER_REQUESTED
            InternalDisconnectReason.DEVICE_POWERED_OFF -> ConnectionState.DisconnectReason.DEVICE_POWERED_OFF
            InternalDisconnectReason.TIMEOUT -> ConnectionState.DisconnectReason.TIMEOUT
            InternalDisconnectReason.OUT_OF_RANGE -> ConnectionState.DisconnectReason.OUT_OF_RANGE
            InternalDisconnectReason.GATT_ERROR -> ConnectionState.DisconnectReason.GATT_ERROR
            InternalDisconnectReason.UNKNOWN -> ConnectionState.DisconnectReason.UNKNOWN
        }
    }

    // ============================================================================================
    // ConnectionState
    // ============================================================================================

    fun toPublic(state: InternalConnectionState): ConnectionState {
        return when (state) {
            is InternalConnectionState.Disconnected -> {
                ConnectionState.Disconnected(
                    reason = toPublic(state.reason),
                    timestamp = state.timestamp
                )
            }
            is InternalConnectionState.Connecting -> {
                ConnectionState.Connecting(
                    timestamp = state.timestamp
                )
            }
            is InternalConnectionState.Connected -> {
                ConnectionState.Connected(
                    timestamp = state.timestamp,
                    mtu = state.mtu
                )
            }
            is InternalConnectionState.Disconnecting -> {
                ConnectionState.Disconnecting(
                    timestamp = state.timestamp
                )
            }
        }
    }

    // ============================================================================================
    // DeviceInfo
    // ============================================================================================

    fun toPublic(info: InternalDeviceInfo): DeviceInfo {
        return DeviceInfo(
            deviceName = info.deviceName,
            modelNumber = info.modelNumber,
            firmwareRevision = info.firmwareRevision,
            manufacturer = info.manufacturer,
            macAddress = info.macAddress,
            batteryLevel = info.batteryLevel,
            rssi = info.rssi,
            isConnected = info.isConnected,
            lastUpdated = info.lastUpdated
        )
    }

    // ============================================================================================
    // DeviceState
    // ============================================================================================

    fun toPublic(state: InternalDeviceState): DeviceState {
        return DeviceState(
            macAddress = state.macAddress,
            connectionState = toPublic(state.connectionState),
            deviceInfo = state.deviceInfo?.let { toPublic(it) },
            lastSeenTimestamp = state.lastSeenTimestamp
        )
    }
}