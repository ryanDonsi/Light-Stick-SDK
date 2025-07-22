package io.lightstick.sdk.ble.util

import java.util.UUID

/**
 * Contains UUID constants for various GATT services and characteristics used in LightStick BLE SDK.
 */
object UuidConstants {

    /** Client Characteristic Configuration Descriptor (CCCD)*/
    val CLIENT_CONFIG_CHAR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ===== Device Information (Standard) =====

    /** Standard BLE Device Information service (manufacturer, model, firmware, etc.) */
    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")

    /** Manufacturer name (e.g., LightStick Inc.) */
    val MANUFACTURER_NAME_CHAR: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")

    /** Device model number (e.g., LS-2024) */
    val MODEL_NUMBER_CHAR: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")

    /** Firmware revision string (e.g., v1.0.3) */
    val FW_REVISION_CHAR: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

    /** Device name shown to users (e.g., “LightStick A”) */
    val DEVICE_NAME_CHAR: UUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")

    // ===== Battery (Standard) =====

    /** Standard BLE Battery service */
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

    /** Battery level characteristic (0–100%) */
    val BATTERY_LEVEL_CHAR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    // ===== LED Control (Custom) =====

    /** Custom service for LED control functions */
    val LED_CONTROL_SERVICE: UUID = UUID.fromString("0001fe01-0000-1000-8000-00805f9800c4")

    /** Sends LED RGB color [R,G,B,transition] */
    val LED_COLOR_CONTROL_CHAR: UUID = UUID.fromString("0001ff01-0000-1000-8000-00805f9800c4")

    /** Sends LED effect binary payload */
    val LED_EFFECT_PAYLOAD_CHAR: UUID = UUID.fromString("0001ff02-0000-1000-8000-00805f9800c4")

    // ===== Firmware Update (OTA) =====

    /** Custom service for OTA firmware update */
    val FIRMWARE_UPDATE_SERVICE: UUID = UUID.fromString("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0")

    /** Characteristic to transmit OTA firmware binary */
    val OTA_CHAR: UUID = UUID.fromString("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f1")

    // ===== Concert Library =====

    /** Service for syncing effect/concert data (e.g., during live event) */
    val CONCERT_LIBRARY_SERVICE: UUID = UUID.fromString("0001fe02-0000-1000-8000-00805f9800c4")

    /** Characteristic for uploading/downloading concert effect binaries */
    val CONCERT_LIBRARY_CHAR: UUID = UUID.fromString("0001ff04-0000-1000-8000-00805f9800c4")

    // ===== MAC Address =====

    /** Service to access internal MAC address of the BLE device */
    val MAC_ADDRESS_SERVICE: UUID = UUID.fromString("0001fe03-0000-1000-8000-00805f9800c4")

    /** Characteristic to read MAC address string (not always the advertising address) */
    val MAC_ADDRESS_CHAR: UUID = UUID.fromString("0001ff05-0000-1000-8000-00805f9800c4")

    // ===== Concert ID =====

    /** Service to get/set concert (event) ID identifier */
    val CONCERT_ID_SERVICE: UUID = UUID.fromString("0001fe04-0000-1000-8000-00805f9800c4")

    /** Characteristic holding the concert ID value (integer or string) */
    val CONCERT_CHAR: UUID = UUID.fromString("0001ff06-0000-1000-8000-00805f9800c4")
}
