package com.lightstick.samples

import com.lightstick.device.Device
import com.lightstick.events.EventAction
import com.lightstick.events.EventManager
import com.lightstick.events.EventRule
import com.lightstick.events.EventTarget.ALL_CONNECTED
import com.lightstick.events.EventTarget.THIS_DEVICE
import com.lightstick.events.EventTrigger
import com.lightstick.events.EventType
import com.lightstick.events.NotificationListenerBridge
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload

/**
 * Usage samples for the public Event API.
 */
object EventSamples {

    // --------------------------------------------------------------------------------------------
    // Lifecycle
    // --------------------------------------------------------------------------------------------

    /** Enable the event pipeline. */
    fun sampleEnableEventPipeline() {
        EventManager.enable()
    }

    /** Disable the event pipeline. */
    fun sampleDisableEventPipeline() {
        EventManager.disable()
    }

    // --------------------------------------------------------------------------------------------
    // Global rules (ALL_CONNECTED)
    // --------------------------------------------------------------------------------------------

    /** Set global rules — example: blink BLUE on custom event. */
    fun sampleSetGlobalRules() {
        val rules = listOf(
            EventRule(
                id = "custom-blue-blink",
                trigger = EventTrigger(type = EventType.CUSTOM),
                action = EventAction.SendEffectFrame(
                    bytes20 = LSEffectPayload.Effects
                        .blink(8, Colors.BLUE)
                        .toByteArray()
                ),
                target = ALL_CONNECTED,
                stopAfterMatch = true
            )
        )
        EventManager.setGlobalRules(rules)
    }

    /** Clear all global rules. */
    fun sampleClearGlobalRules() {
        EventManager.clearGlobalRules()
    }

    /** Get current global rules. */
    fun sampleGetGlobalRules(): List<EventRule> = EventManager.getGlobalRules()

    // --------------------------------------------------------------------------------------------
    // Device-scoped rules (THIS_DEVICE)
    // --------------------------------------------------------------------------------------------

    /** Set device-scoped rules for a specific [device]. */
    fun sampleSetDeviceRules(device: Device) {
        val rules = listOf(
            EventRule(
                id = "device-custom-white",
                trigger = EventTrigger(type = EventType.CUSTOM),
                action = EventAction.SendEffectFrame(
                    bytes20 = LSEffectPayload.Effects
                        .on(Colors.WHITE)
                        .toByteArray()
                ),
                target = THIS_DEVICE
            )
        )
        EventManager.setDeviceRules(device.mac, rules)
    }

    /** Clear device-scoped rules for [device]. */
    fun sampleClearDeviceRules(device: Device) {
        EventManager.clearDeviceRules(device.mac)
    }

    /** Get device-scoped rules for [device]. */
    fun sampleGetDeviceRules(device: Device): List<EventRule> =
        EventManager.getDeviceRules(device.mac)

    // --------------------------------------------------------------------------------------------
    // Snapshot (global + per-device)
    // --------------------------------------------------------------------------------------------

    /** Get a snapshot of all rules (global + per-device). */
    fun sampleGetAllRules(): EventManager.Snapshot = EventManager.getAllRules()

    // --------------------------------------------------------------------------------------------
    // Mapping-style samples
    // --------------------------------------------------------------------------------------------

    fun sampleMapGlobalRule(): List<EventRule> {
        val rule = EventRule(
            id = "global-custom-green-strobe",
            trigger = EventTrigger(type = EventType.CUSTOM),
            action = EventAction.SendEffectFrame(
                bytes20 = LSEffectPayload.Effects
                    .strobe(4, Colors.GREEN)
                    .toByteArray()
            ),
            target = ALL_CONNECTED
        )
        return listOf(rule)
    }

    fun sampleMapDeviceRule(device: Device): Pair<String, List<EventRule>> {
        val rule = EventRule(
            id = "device-custom-breath",
            trigger = EventTrigger(type = EventType.CUSTOM),
            action = EventAction.SendEffectFrame(
                bytes20 = LSEffectPayload.Effects
                    .breath(12, Colors.PURPLE)
                    .toByteArray()
            ),
            target = THIS_DEVICE
        )
        return device.mac to listOf(rule)
    }

    fun sampleMapFromInternalGlobal(): List<EventRule> = EventManager.getGlobalRules()

    fun sampleMapFromInternalDevice(device: Device): List<EventRule> =
        EventManager.getDeviceRules(device.mac)

    // --------------------------------------------------------------------------------------------
    // EventType usage sample
    // --------------------------------------------------------------------------------------------

    fun sampleEventTypeUsage(type: EventType): EventRule {
        return when (type) {
            EventType.CUSTOM -> EventRule(
                id = "custom-orange-breath",
                trigger = EventTrigger(type),
                action = EventAction.SendEffectFrame(
                    bytes20 = LSEffectPayload.Effects.breath(14, Colors.ORANGE).toByteArray()
                ),
                target = ALL_CONNECTED
            )
        }
    }

    // --------------------------------------------------------------------------------------------
    // Notification bridge
    // --------------------------------------------------------------------------------------------

    fun sampleNotificationListener() {
        // No-op at runtime; the point is to show the subclass below and manifest wiring.
    }

    /**
     * Minimal NotificationListener subclass you can register in AndroidManifest.
     *
     * <service
     *   android:name=".MyNotificationListener"
     *   android:label="LightStick Notifications"
     *   android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
     *   <intent-filter>
     *     <action android:name="android.service.notification.NotificationListenerService" />
     *   </intent-filter>
     * </service>
     */
    abstract class MyNotificationListener : NotificationListenerBridge()

    // --------------------------------------------------------------------------------------------
    // Event DTO construction samples (for KDoc)
    // --------------------------------------------------------------------------------------------

    fun sampleEventTriggerUsage(): EventTrigger =
        EventTrigger(type = EventType.CUSTOM)

    fun sampleSendColorAction(): EventAction.SendColorPacket =
        EventAction.SendColorPacket(byteArrayOf(255.toByte(), 0, 0, 8))

    fun sampleSendEffectAction(): EventAction.SendEffectFrame =
        EventAction.SendEffectFrame(
            bytes20 = LSEffectPayload.Effects.blink(10, Colors.BLUE).toByteArray()
        )

    fun samplePlayFramesAction(): EventAction.PlayFrames {
        val entries = listOf(
            0L to LSEffectPayload.Effects.on(Colors.WHITE).toByteArray(),
            300L to LSEffectPayload.Effects.off().toByteArray()
        )
        return EventAction.PlayFrames(entries)
    }

    fun sampleEventActionUsage(): List<EventAction> = listOf(
        sampleSendColorAction(),
        sampleSendEffectAction(),
        samplePlayFramesAction()
    )

    fun sampleBuildEventRule(): EventRule =
        EventRule(
            id = "demo-rule",
            trigger = sampleEventTriggerUsage(),
            action = sampleSendEffectAction(),
            target = ALL_CONNECTED,
            stopAfterMatch = true
        )
}
