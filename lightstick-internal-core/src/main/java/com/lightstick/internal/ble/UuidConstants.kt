package com.lightstick.internal.ble

import java.util.UUID

/**
 * Centralized UUID constants used by the internal BLE layer.
 *
 * Keeping UUIDs in one place avoids scattering literals in code and simplifies
 * product-wide updates. Ensure these values match the current **product UUID plan**.
 *
 * @since 1.0.0
 */
internal object UuidConstants {

    // ===== CCCD (Descriptor) ==================================================
    /** Client Characteristic Configuration Descriptor UUID (표준 0x2902) */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** CCCD: Notifications 활성화 (0x0100) */
    val CCCD_ENABLE_NOTIFICATION: ByteArray = byteArrayOf(0x01, 0x00)
    /** CCCD: Indications 활성화 (0x0200) */
    val CCCD_ENABLE_INDICATION: ByteArray = byteArrayOf(0x02, 0x00)
    /** CCCD: 비활성화 (0x0000) */
    val CCCD_DISABLE: ByteArray = byteArrayOf(0x00, 0x00)

    // ===== LED Control (Custom) ==============================================
    /** LED Control Service (LCS). */
    val LCS_SERVICE: UUID = UUID.fromString("0001fe01-0000-1000-8000-00805f9800c4")

    /** 4B color packet characteristic: [R, G, B, transition]. */
    val LCS_COLOR: UUID = UUID.fromString("0001ff01-0000-1000-8000-00805f9800c4")
    /** 16B effect payload characteristic (LSEffectPayload v1.3). */
    val LCS_PAYLOAD: UUID = UUID.fromString("0001ff02-0000-1000-8000-00805f9800c4")

    // ===== Battery Service (Standard) ========================================
    /** Battery Service (BAS). */
    val BAS_SERVICE: UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")

    /** Battery Level characteristic (0..100%). */
    val BAS_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    // ===== Device Information (Standard) =====================================
    /** Device Information Service (DIS). */
    val DIS_SERVICE: UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb")

    /** Device name shown to users (e.g., “LightStick A”) */
    val DIS_DEVICE_NAME: UUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
    /** Model Number String. */
    val DIS_MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    /** Firmware revision string (e.g., v1.0.3) */
    val DIS_FW_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    /** Manufacturer Name String. */
    val DIS_MANUFACTURER: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")


    // ===== MAC (Custom) ======================================================
    /** Custom service exposing internal MAC address string (if separate from adv MAC). */
    val MAC_SERVICE: UUID = UUID.fromString("0001fe03-0000-1000-8000-00805f9800c4")

    /** Internal MAC address characteristic. */
    val MAC_CHAR: UUID = UUID.fromString("0001ff05-0000-1000-8000-00805f9800c4")

    // ===== OTA (Telink style) ================================================
    /** OTA Update Service. */
    val OTA_SERVICE: UUID = UUID.fromString("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0")

    /** OTA Data characteristic (write/notify as per protocol). */
    val OTA_DATA: UUID = UUID.fromString("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f1")

    // ===== Concert Library (Optional Custom) =================================
    /** Concert Library Service (optional, for batch EFX sync). */
    val CONCERT_SERVICE: UUID = UUID.fromString("0001fe02-0000-1000-8000-00805f9800c4")

    /** Concert data characteristic. */
    val CONCERT_CHAR: UUID = UUID.fromString("0001ff04-0000-1000-8000-00805f9800c4")
}
