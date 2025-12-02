@file:Suppress("MissingPermission")

package com.lightstick.samples

import android.content.Context
import android.util.Log
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.events.EventAction
import com.lightstick.events.EventFilter
import com.lightstick.events.EventRule
import com.lightstick.events.EventTarget
import com.lightstick.events.EventTrigger
import com.lightstick.events.EventType
import com.lightstick.types.Color
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sample usages referenced by KDoc in public APIs (Device/Controller).
 * These are illustrative only; they assume you observe results/progress/status through your
 * own Facade → ViewModel → UI pipeline (Flows/State, etc.).
 */
object DeviceSamples {

    // --------------------------------------------------------------------------------------------
    // Connect / Disconnect
    // --------------------------------------------------------------------------------------------

    /**
     * Sample: Initialize, connect to a device, use both Controller and Device-level APIs.
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
            onConnected = { controller ->
                Log.d("Sample", "Connected to ${controller.device.mac}")

                // Controller-scoped calls
                controller.sendColor(Colors.BLUE, transition = 8)
                controller.sendEffect(LSEffectPayload.Effects.blink(6, Colors.RED))

                val frames = listOf(
                    0L to LSEffectPayload.Effects.on(Colors.WHITE).toByteArray(),
                    250L to LSEffectPayload.Effects.strobe(4, Colors.CYAN).toByteArray()
                )
                controller.play(frames)

                controller.requestMtu(247) { result ->
                    result
                        .onSuccess { mtu -> Log.d("Sample", "Negotiated MTU=$mtu") }
                        .onFailure { t -> Log.w("Sample", "MTU failed: ${t.message}", t) }
                }

                // Example read submissions via Controller (values are delivered via Result)
                controller.readDeviceName { r -> r.onFailure { t -> Log.w("Sample", "readDeviceName failed", t) } }
                controller.readModelNumber { r -> r.onFailure { t -> Log.w("Sample", "readModelNumber failed", t) } }
                controller.readFirmwareRevision { r -> r.onFailure { t -> Log.w("Sample", "readFirmwareRevision failed", t) } }
                controller.readManufacturer { r -> r.onFailure { t -> Log.w("Sample", "readManufacturer failed", t) } }
                controller.readMacAddress { r -> r.onFailure { t -> Log.w("Sample", "readMacAddress failed", t) } }
                controller.readBattery { r -> r.onFailure { t -> Log.w("Sample", "readBattery failed", t) } }

                // OTA start example (Controller path)
                val fakeFirmware = ByteArray(1024) { 0x5A }
                controller.startOta(
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
        Log.d("Sample", "Disconnect submitted=$ok")
    }

    // --------------------------------------------------------------------------------------------
    // Connection / Bond state
    // --------------------------------------------------------------------------------------------

    /** Sample: Check if a device is connected. */
    @JvmStatic
    fun sampleIsConnected(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        Log.d("Sample", "isConnected=${device.isConnected()}")
    }

    /** Sample: Check if a device is bonded (paired). */
    @JvmStatic
    fun sampleIsBonded(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        Log.d("Sample", "isBonded=${device.isBonded()}")
    }

    /** Sample: Initiate system bonding (pairing). */
    @JvmStatic
    fun sampleBond(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        val submitted = device.bond(onDone = { Log.d("Sample", "Bonded OK (or already bonded)") })
        Log.d("Sample", "bond submitted=$submitted")
    }

    /** Sample: Request system unbond (remove pairing). */
    @JvmStatic
    fun sampleUnbond(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        val submitted = device.unbond(onDone = { Log.d("Sample", "Unbond OK") })
        Log.d("Sample", "unbond submitted=$submitted")
    }

    // --------------------------------------------------------------------------------------------
    // Device-level convenience: Color / Effect / Play / MTU
    // --------------------------------------------------------------------------------------------

    /** Sample: Send a color via Device helper. */
    @JvmStatic
    fun sampleSendColor(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) {
            Log.w("Sample", "Not connected"); return
        }
        val ok = device.sendColor(Color(255, 128, 0), transition = 6)
        Log.d("Sample", "sendColor submitted=$ok")
    }

    /** Sample: Send a 20B effect via Device helper. */
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
        if (!device.isConnected()) { Log.w("Sample", "Not connected"); return }

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
            r.onSuccess { v -> name = v }.onFailure { t -> Log.w("Sample", "readDeviceName failed: ${t.message}", t) }
            completeOne()
        }
        device.readModelNumber { r ->
            r.onSuccess { v -> model = v }.onFailure { t -> Log.w("Sample", "readModelNumber failed: ${t.message}", t) }
            completeOne()
        }
        device.readFirmwareRevision { r ->
            r.onSuccess { v -> fw = v }.onFailure { t -> Log.w("Sample", "readFirmwareRevision failed: ${t.message}", t) }
            completeOne()
        }
        device.readManufacturer { r ->
            r.onSuccess { v -> mfr = v }.onFailure { t -> Log.w("Sample", "readManufacturer failed: ${t.message}", t) }
            completeOne()
        }
    }

    // (Optional) individual submit-only helpers, if you want them referenced elsewhere
    @JvmStatic fun sampleReadDeviceName(context: Context) { LSBluetooth.initialize(context); Device("AA:BB:CC:DD:EE:FF").apply {
        if (!isConnected()) { Log.w("Sample","Not connected"); return }
        readDeviceName { r -> r.onSuccess { Log.d("Sample","DeviceName=$it") }.onFailure { t -> Log.w("Sample","readDeviceName failed", t) } }
    } }

    @JvmStatic fun sampleReadModelNumber(context: Context) { LSBluetooth.initialize(context); Device("AA:BB:CC:DD:EE:FF").apply {
        if (!isConnected()) { Log.w("Sample","Not connected"); return }
        readModelNumber { r -> r.onSuccess { Log.d("Sample","ModelNumber=$it") }.onFailure { t -> Log.w("Sample","readModelNumber failed", t) } }
    } }

    @JvmStatic fun sampleReadFirmwareRevision(context: Context) { LSBluetooth.initialize(context); Device("AA:BB:CC:DD:EE:FF").apply {
        if (!isConnected()) { Log.w("Sample","Not connected"); return }
        readFirmwareRevision { r -> r.onSuccess { Log.d("Sample","FirmwareRevision=$it") }.onFailure { t -> Log.w("Sample","readFirmwareRevision failed", t) } }
    } }

    @JvmStatic fun sampleReadManufacturer(context: Context) { LSBluetooth.initialize(context); Device("AA:BB:CC:DD:EE:FF").apply {
        if (!isConnected()) { Log.w("Sample","Not connected"); return }
        readManufacturer { r -> r.onSuccess { Log.d("Sample","Manufacturer=$it") }.onFailure { t -> Log.w("Sample","readManufacturer failed", t) } }
    } }

    @JvmStatic fun sampleReadMacAddress(context: Context) { LSBluetooth.initialize(context); Device("AA:BB:CC:DD:EE:FF").apply {
        if (!isConnected()) { Log.w("Sample","Not connected"); return }
        readMacAddress { r -> r.onSuccess { Log.d("Sample","MacAddress=$it") }.onFailure { t -> Log.w("Sample","readMacAddress failed", t) } }
    } }

    @JvmStatic fun sampleReadBattery(context: Context) { LSBluetooth.initialize(context); Device("AA:BB:CC:DD:EE:FF").apply {
        if (!isConnected()) { Log.w("Sample","Not connected"); return }
        readBattery { r -> r.onSuccess { Log.d("Sample","Battery=$it%") }.onFailure { t -> Log.w("Sample","readBattery failed", t) } }
    } }

    // --------------------------------------------------------------------------------------------
    // OTA (Device helper path)
    // --------------------------------------------------------------------------------------------

    /** Sample: Start OTA from bytes via Device helper. */
    @JvmStatic
    fun sampleStartOta(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) { Log.w("Sample", "Not connected"); return }

        val fakeFirmware = ByteArray(4096) { 0x7E }
        val ok = device.startOta(
            firmware = fakeFirmware,
            onProgress = { p -> Log.d("Sample", "OTA $p%") },
            onResult = { r ->
                r.onSuccess { Log.d("Sample", "OTA completed") }
                    .onFailure { t -> Log.w("Sample", "OTA failed: ${t.message}", t) }
            }
        )
        Log.d("Sample", "startOta submitted=$ok")
    }

    /** Sample: Abort an ongoing OTA via Device helper. */
    @JvmStatic
    fun sampleAbortOta(context: Context) {
        LSBluetooth.initialize(context)
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        if (!device.isConnected()) { Log.w("Sample", "Not connected"); return }
        val ok = device.abortOta()
        Log.d("Sample", "abortOta submitted=$ok")
    }

    // --------------------------------------------------------------------------------------------
    // Events (device-scoped)
    // --------------------------------------------------------------------------------------------

    /** Sample: Register device-scoped rules on a specific device. */
    @JvmStatic
    fun sampleRegisterDeviceRules() {
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")

        val rules = listOf(
            // SMS contains "sale" → blink RED on THIS_DEVICE
            EventRule(
                trigger = EventTrigger(
                    type = EventType.SMS_RECEIVED,
                    filter = EventFilter(smsContains = "sale")
                ),
                action = EventAction.SendColorPacket(
                    packet4 = byteArrayOf(255.toByte(), 0, 0, 8) // R,G,B,transition
                ),
                target = EventTarget.THIS_DEVICE,
                stopAfterMatch = true
            ),

            // Calendar title == "Meeting" → short 2-step timeline
            EventRule(
                trigger = EventTrigger(
                    type = EventType.CALENDAR_START,
                    filter = EventFilter(calendarTitle = "Meeting")
                ),
                action = EventAction.PlayFrames(
                    entries = listOf(
                        0L to LSEffectPayload.Effects.on(Colors.CYAN).toByteArray(),
                        600L to LSEffectPayload.Effects.off().toByteArray()
                    )
                ),
                target = EventTarget.THIS_DEVICE,
                stopAfterMatch = true
            )
        )

        device.registerEventRules(rules)
    }

    /** Sample: Clear all device-scoped rules for a specific device. */
    @JvmStatic
    fun sampleClearDeviceRules() {
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        device.clearEventRules()
    }

    /** Sample: Get current device-scoped rules for THIS device. */
    @JvmStatic
    fun sampleGetDeviceRules() {
        val device = Device(mac = "AA:BB:CC:DD:EE:FF")
        val rules: List<EventRule> = device.getEventRules()
        Log.d("Sample", "Device(${device.mac}) rules count=${rules.size}")
        rules.forEachIndexed { idx, r ->
            Log.d(
                "Sample",
                "[#$idx] id=${r.id ?: "(none)"} type=${r.trigger.type} target=${r.target} stop=${r.stopAfterMatch}"
            )
        }
    }
}
