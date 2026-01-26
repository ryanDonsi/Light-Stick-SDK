@file:Suppress("MissingPermission")

package com.lightstick.samples

import android.content.Context
import android.util.Log
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.events.EventRule
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sample usages referenced by KDoc in public APIs (Device).
 * These are illustrative only; they assume you observe results/progress/status through your
 * own Facade → ViewModel → UI pipeline (Flows/State, etc.).
 */
object DeviceSamples {

    // --------------------------------------------------------------------------------------------
    // Connect / Disconnect
    // --------------------------------------------------------------------------------------------

    /**
     * Sample: Initialize, connect to a device, use Device-level APIs.
     *
     * Notes:
     * - Replace "AA:BB:CC:DD:EE:FF" with your target MAC.
     * - Ensure BLUETOOTH_* permissions are granted before invoking.
     */
    @JvmStatic
    fun sampleConnectDevice(context: Context) {
        LSBluetooth.initialize(context)

        val device = Device(mac = "AA:BB:CC:DD:EE:FF", name = "LightStick-01", rssi = -60)

        device.connect(
            onConnected = {
                Log.d("Sample", "Connected to ${device.mac}")

                // Device-scoped calls
                device.sendColor(Colors.BLUE, transition = 8)
                device.sendEffect(LSEffectPayload.Effects.blink(6, Colors.RED))

                val frames = listOf(
                    0L to LSEffectPayload.Effects.on(Colors.WHITE).toByteArray(),
                    250L to LSEffectPayload.Effects.strobe(4, Colors.CYAN).toByteArray()
                )
                device.play(frames)

                device.requestMtu(247)

                // Example read submissions via Device (values are delivered via Result)
                device.readDeviceName { r ->
                    r.onSuccess { name -> Log.d("Sample", "Device name: $name") }
                        .onFailure { t -> Log.w("Sample", "readDeviceName failed", t) }
                }
                device.readModelNumber { r ->
                    r.onFailure { t -> Log.w("Sample", "readModelNumber failed", t) }
                }
                device.readFirmwareRevision { r ->
                    r.onFailure { t -> Log.w("Sample", "readFirmwareRevision failed", t) }
                }
                device.readManufacturer { r ->
                    r.onFailure { t -> Log.w("Sample", "readManufacturer failed", t) }
                }
                device.readMacAddress { r ->
                    r.onFailure { t -> Log.w("Sample", "readMacAddress failed", t) }
                }
                device.readBattery { r ->
                    r.onSuccess { level -> Log.d("Sample", "Battery: $level%") }
                        .onFailure { t -> Log.w("Sample", "readBattery failed", t) }
                }

                // OTA start example
                val fakeFirmware = ByteArray(1024) { 0x5A }
                device.startOta(
                    firmware = fakeFirmware,
                    onProgress = { p -> Log.d("Sample", "OTA progress=$p%") },
                    onResult = { r ->
                        r.onSuccess { Log.d("Sample", "OTA completed") }
                            .onFailure { t -> Log.w("Sample", "OTA failed: ${t.message}", t) }
                    }
                )
            },
            onFailed = { t ->
                Log.w("Sample", "Connect failed: ${t.message}", t)
            },
            onDeviceInfo = { info ->
                Log.d("Sample", "DeviceInfo received:")
                Log.d("Sample", "  Name: ${info.deviceName}")
                Log.d("Sample", "  Model: ${info.modelNumber}")
                Log.d("Sample", "  FW: ${info.firmwareRevision}")
                Log.d("Sample", "  Manufacturer: ${info.manufacturer}")
            }
        )

        // Device-level convenience calls after connection is established
        if (device.isConnected()) {
            device.sendColor(Colors.GREEN, transition = 4)
            device.sendEffect(LSEffectPayload.Effects.breath(10, Colors.MAGENTA))
        }
    }

    /** Sample: Disconnect from a specific device. */
    @JvmStatic
    fun sampleDisconnect(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        val ok = device.disconnect()
        Log.d("Sample", "disconnect submitted=$ok")
    }

    // --------------------------------------------------------------------------------------------
    // Bonding
    // --------------------------------------------------------------------------------------------

    /** Sample: Bond (pair) with a device. */
    @JvmStatic
    fun sampleBond(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        val ok = device.bond(
            onDone = { Log.d("Sample", "Device bonded") }
        )
        Log.d("Sample", "bond submitted=$ok")
    }

    /** Sample: Unbond (unpair) a device. */
    @JvmStatic
    fun sampleUnbond(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        val ok = device.unbond(
            onDone = { Log.d("Sample", "Device unbonded") }
        )
        Log.d("Sample", "unbond submitted=$ok")
    }

    // --------------------------------------------------------------------------------------------
    // Connection / Bond state
    // --------------------------------------------------------------------------------------------

    /** Sample: Check if device is connected. */
    @JvmStatic
    fun sampleIsConnected(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        val connected = device.isConnected()
        Log.d("Sample", "isConnected=$connected")
    }

    /** Sample: Check if device is bonded. */
    @JvmStatic
    fun sampleIsBonded(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        val bonded = device.isBonded()
        Log.d("Sample", "isBonded=$bonded")
    }

    // --------------------------------------------------------------------------------------------
    // LED Control
    // --------------------------------------------------------------------------------------------

    /** Sample: Send a color via Device helper. */
    @JvmStatic
    fun sampleSendColor(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) {
            Log.w("Sample", "Not connected"); return
        }
        val ok = device.sendColor(Colors.RED, transition = 10)
        Log.d("Sample", "sendColor submitted=$ok")
    }

    /** Sample: Send an effect via Device helper. */
    @JvmStatic
    fun sampleSendEffect(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) {
            Log.w("Sample", "Not connected"); return
        }
        val payload = LSEffectPayload.Effects.strobe(5, Colors.CYAN)
        val ok = device.sendEffect(payload)
        Log.d("Sample", "sendEffect submitted=$ok")
    }

    /** Sample: Play timestamped frames via Device helper. */
    @JvmStatic
    fun samplePlayFrames(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) {
            Log.w("Sample", "Not connected"); return
        }
        val frames = listOf(
            0L to LSEffectPayload.Effects.on(Colors.WHITE).toByteArray(),
            200L to LSEffectPayload.Effects.blink(4, Colors.BLUE).toByteArray(),
            400L to LSEffectPayload.Effects.off().toByteArray()
        )
        val ok = device.play(frames)
        Log.d("Sample", "play submitted=$ok")
    }

    // --------------------------------------------------------------------------------------------
    // Timeline Playback (Music Sync)
    // --------------------------------------------------------------------------------------------

    /** Sample: Load timeline for music-synchronized playback. */
    @JvmStatic
    fun sampleLoadTimeline(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) {
            Log.w("Sample", "Not connected"); return
        }

        val frames = listOf(
            0L to LSEffectPayload.Effects.on(Colors.WHITE).toByteArray(),
            1000L to LSEffectPayload.Effects.blink(4, Colors.BLUE).toByteArray(),
            2000L to LSEffectPayload.Effects.strobe(3, Colors.RED).toByteArray()
        )
        val ok = device.loadTimeline(frames)
        Log.d("Sample", "loadTimeline submitted=$ok")
    }

    /** Sample: Update playback position. */
    @JvmStatic
    fun sampleUpdatePlaybackPosition(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) {
            Log.w("Sample", "Not connected"); return
        }

        val currentPositionMs = 1500L
        val ok = device.updatePlaybackPosition(currentPositionMs)
        Log.d("Sample", "updatePlaybackPosition submitted=$ok")
    }

    /** Sample: Pause effects. */
    @JvmStatic
    fun samplePauseEffects(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) {
            Log.w("Sample", "Not connected"); return
        }

        val ok = device.pauseEffects()
        Log.d("Sample", "pauseEffects submitted=$ok")
    }

    /** Sample: Resume effects. */
    @JvmStatic
    fun sampleResumeEffects(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) {
            Log.w("Sample", "Not connected"); return
        }

        val ok = device.resumeEffects()
        Log.d("Sample", "resumeEffects submitted=$ok")
    }

    /** Sample: Stop timeline. */
    @JvmStatic
    fun sampleStopTimeline(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) {
            Log.w("Sample", "Not connected"); return
        }

        val ok = device.stopTimeline()
        Log.d("Sample", "stopTimeline submitted=$ok")
    }

    /** Sample: Check if timeline is playing. */
    @JvmStatic
    fun sampleIsTimelinePlaying(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) {
            Log.w("Sample", "Not connected"); return
        }

        val playing = device.isTimelinePlaying()
        Log.d("Sample", "isTimelinePlaying=$playing")
    }

    // --------------------------------------------------------------------------------------------
    // MTU Negotiation
    // --------------------------------------------------------------------------------------------

    /** Sample: Request MTU via Device helper. */
    @JvmStatic
    fun sampleRequestMtu(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) {
            Log.w("Sample", "Not connected"); return
        }
        val ok = device.requestMtu(preferred = 247)
        Log.d("Sample", "requestMtu submitted=$ok")
        // Note: negotiated MTU should be observed via your own listener/Flow.
    }

    // --------------------------------------------------------------------------------------------
    // Device Information reads
    // (submit-only helpers + one aggregate example that logs 4 DIS values)
    // --------------------------------------------------------------------------------------------

    /**
     * Aggregate example that reads 4 DIS fields and logs them when all have returned:
     * - Device Name (2A00)
     * - Model Number (2A24)
     * - Firmware Revision (2A26)
     * - Manufacturer Name (2A29)
     *
     * Shows how to use Result<T>-style read APIs directly.
     */
    @JvmStatic
    fun sampleReadDeviceInfo(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) {
            Log.w("Sample", "Not connected"); return
        }

        var name: String? = null
        var model: String? = null
        var fw: String? = null
        var mfr: String? = null

        val total = 4
        val done = AtomicInteger(0)
        fun completeOne() {
            if (done.incrementAndGet() == total) {
                Log.d("Sample", "DIS snapshot: name=$name, model=$model, fw=$fw, mfr=$mfr")
            }
        }

        device.readDeviceName { r ->
            r.onSuccess { v -> name = v }
                .onFailure { t -> Log.w("Sample", "readDeviceName failed: ${t.message}", t) }
            completeOne()
        }
        device.readModelNumber { r ->
            r.onSuccess { v -> model = v }
                .onFailure { t -> Log.w("Sample", "readModelNumber failed: ${t.message}", t) }
            completeOne()
        }
        device.readFirmwareRevision { r ->
            r.onSuccess { v -> fw = v }
                .onFailure { t -> Log.w("Sample", "readFirmwareRevision failed: ${t.message}", t) }
            completeOne()
        }
        device.readManufacturer { r ->
            r.onSuccess { v -> mfr = v }
                .onFailure { t -> Log.w("Sample", "readManufacturer failed: ${t.message}", t) }
            completeOne()
        }
    }

    // Individual submit-only helpers
    @JvmStatic
    fun sampleReadDeviceName(context: Context) {
        LSBluetooth.initialize(context)
        Device("AA:BB:CC:DD:EE:FF").apply {
            if (!isConnected()) { Log.w("Sample","Not connected"); return }
            readDeviceName { r ->
                r.onSuccess { Log.d("Sample","DeviceName=$it") }
                    .onFailure { t -> Log.w("Sample","readDeviceName failed", t) }
            }
        }
    }

    @JvmStatic
    fun sampleReadModelNumber(context: Context) {
        LSBluetooth.initialize(context)
        Device("AA:BB:CC:DD:EE:FF").apply {
            if (!isConnected()) { Log.w("Sample","Not connected"); return }
            readModelNumber { r ->
                r.onSuccess { Log.d("Sample","ModelNumber=$it") }
                    .onFailure { t -> Log.w("Sample","readModelNumber failed", t) }
            }
        }
    }

    @JvmStatic
    fun sampleReadFirmwareRevision(context: Context) {
        LSBluetooth.initialize(context)
        Device("AA:BB:CC:DD:EE:FF").apply {
            if (!isConnected()) { Log.w("Sample","Not connected"); return }
            readFirmwareRevision { r ->
                r.onSuccess { Log.d("Sample","FirmwareRevision=$it") }
                    .onFailure { t -> Log.w("Sample","readFirmwareRevision failed", t) }
            }
        }
    }

    @JvmStatic
    fun sampleReadManufacturer(context: Context) {
        LSBluetooth.initialize(context)
        Device("AA:BB:CC:DD:EE:FF").apply {
            if (!isConnected()) { Log.w("Sample","Not connected"); return }
            readManufacturer { r ->
                r.onSuccess { Log.d("Sample","Manufacturer=$it") }
                    .onFailure { t -> Log.w("Sample","readManufacturer failed", t) }
            }
        }
    }

    @JvmStatic
    fun sampleReadMacAddress(context: Context) {
        LSBluetooth.initialize(context)
        Device("AA:BB:CC:DD:EE:FF").apply {
            if (!isConnected()) { Log.w("Sample","Not connected"); return }
            readMacAddress { r ->
                r.onSuccess { Log.d("Sample","MacAddress=$it") }
                    .onFailure { t -> Log.w("Sample","readMacAddress failed", t) }
            }
        }
    }

    @JvmStatic
    fun sampleReadBattery(context: Context) {
        LSBluetooth.initialize(context)
        Device("AA:BB:CC:DD:EE:FF").apply {
            if (!isConnected()) { Log.w("Sample","Not connected"); return }
            readBattery { r ->
                r.onSuccess { Log.d("Sample","Battery=$it%") }
                    .onFailure { t -> Log.w("Sample","readBattery failed", t) }
            }
        }
    }

    // --------------------------------------------------------------------------------------------
    // OTA
    // --------------------------------------------------------------------------------------------

    /** Sample: Start OTA. */
    @JvmStatic
    fun sampleStartOta(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) {
            Log.w("Sample", "Not connected"); return
        }

        val firmware = ByteArray(2048) { 0x42 }
        val ok = device.startOta(
            firmware = firmware,
            onProgress = { percent ->
                Log.d("Sample", "OTA progress: $percent%")
            },
            onResult = { result ->
                result.onSuccess {
                    Log.d("Sample", "OTA completed successfully")
                }.onFailure { t ->
                    Log.w("Sample", "OTA failed: ${t.message}", t)
                }
            }
        )
        Log.d("Sample", "startOta submitted=$ok")
    }

    /** Sample: Abort OTA. */
    @JvmStatic
    fun sampleAbortOta(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) {
            Log.w("Sample", "Not connected"); return
        }

        val ok = device.abortOta()
        Log.d("Sample", "abortOta submitted=$ok")
    }

    // --------------------------------------------------------------------------------------------
    // Event API (device-scoped)
    // --------------------------------------------------------------------------------------------
    // Event API 샘플은 실제 EventRule 구조에 맞게 구현 필요
    // 현재는 placeholder로 남겨둠

    /** Sample: Register device-specific event rules. */
    @JvmStatic
    fun sampleRegisterDeviceRules(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")

        // TODO: 실제 EventRule 구조에 맞게 구현
        val rules = emptyList<EventRule>()

        device.registerEventRules(rules)
        Log.d("Sample", "Registered ${rules.size} device-specific rules")
    }

    /** Sample: Clear device-specific event rules. */
    @JvmStatic
    fun sampleClearDeviceRules(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")

        device.clearEventRules()
        Log.d("Sample", "Cleared device-specific rules")
    }

    /** Sample: Get device-specific event rules. */
    @JvmStatic
    fun sampleGetDeviceRules(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")

        val rules = device.getEventRules()
        Log.d("Sample", "Device has ${rules.size} event rules")
    }
}