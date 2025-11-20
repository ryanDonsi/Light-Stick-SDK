package com.lightstick.samples

import com.lightstick.device.Device
import com.lightstick.events.EventAction
import com.lightstick.events.EventFilter
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
 *
 * Covers:
 * - Enabling/disabling the event pipeline
 * - Setting/clearing **global** rules
 * - Setting/clearing **device-scoped** rules
 * - Querying rules (global / per-device / snapshot)
 * - Mapping-style samples requested by docs/references
 */
object EventSamples {

    // --------------------------------------------------------------------------------------------
    // Lifecycle
    // --------------------------------------------------------------------------------------------

    /** Enable the event pipeline (SMS/Call/Calendar/Notification). */
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

    /**
     * Set global rules (replaces existing). Empty list would clear.
     * Example: Blink BLUE on SMS containing "meet".
     */
    fun sampleSetGlobalRules() {
        val rules = listOf(
            EventRule(
                id = "sms-blue-blink",
                trigger = EventTrigger(
                    type = EventType.SMS_RECEIVED,
                    filter = EventFilter(smsContains = "meet")
                ),
                action = EventAction.SendEffectFrame(
                    bytes20 = LSEffectPayload.Effects
                        .blink(Colors.BLUE, period = 8)
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

    /**
     * Set device-scoped rules for a specific [device] (replaces existing).
     * Example: Turn steady WHITE when calendar event (title contains "Daily") starts.
     */
    fun sampleSetDeviceRules(device: Device) {
        val rules = listOf(
            EventRule(
                id = "calendar-start-white",
                trigger = EventTrigger(
                    type = EventType.CALENDAR_START,
                    filter = EventFilter(calendarTitle = "Daily")
                ),
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
    // Mapping-style samples (names requested)
    // --------------------------------------------------------------------------------------------

    /**
     * sampleMapGlobalRule:
     * Pretend “mapping” from app-level description -> public EventRule.
     * Returns a ready-to-register global rule list.
     */
    fun sampleMapGlobalRule(): List<EventRule> {
        val rule = EventRule(
            id = "global-sms-green-strobe",
            trigger = EventTrigger(
                type = EventType.SMS_RECEIVED,
                filter = EventFilter(smsContains = "ok")
            ),
            action = EventAction.SendEffectFrame(
                bytes20 = LSEffectPayload.Effects
                    .strobe(Colors.GREEN, period = 6)
                    .toByteArray()
            ),
            target = ALL_CONNECTED
        )
        return listOf(rule)
    }

    /**
     * sampleMapDeviceRule:
     * Pretend “mapping” for a given device -> public EventRule.
     * Returns (mac, rules) so the caller can feed EventManager.setDeviceRules(mac, rules).
     */
    fun sampleMapDeviceRule(device: Device): Pair<String, List<EventRule>> {
        val rule = EventRule(
            id = "device-custom-breath",
            trigger = EventTrigger(type = EventType.CUSTOM),
            action = EventAction.SendEffectFrame(
                bytes20 = LSEffectPayload.Effects
                    .breath(Colors.PURPLE, period = 12)
                    .toByteArray()
            ),
            target = THIS_DEVICE
        )
        return device.mac to listOf(rule)
    }

    /**
     * sampleMapFromInternalGlobal:
     * “Mapping from internal” in public space simply means: fetch what engine holds now.
     */
    fun sampleMapFromInternalGlobal(): List<EventRule> {
        return EventManager.getGlobalRules()
    }

    /**
     * sampleMapFromInternalDevice:
     * Same idea for a specific device – fetch what engine holds now.
     */
    fun sampleMapFromInternalDevice(device: Device): List<EventRule> {
        return EventManager.getDeviceRules(device.mac)
    }

    // --------------------------------------------------------------------------------------------
    // EventType usage sample
    // --------------------------------------------------------------------------------------------

    /**
     * sampleEventTypeUsage:
     * Demonstrates branching on [EventType] to build different rules.
     */
    fun sampleEventTypeUsage(type: EventType): EventRule {
        return when (type) {
            EventType.SMS_RECEIVED -> EventRule(
                id = "sms-red-on",
                trigger = EventTrigger(type),
                action = EventAction.SendEffectFrame(
                    bytes20 = LSEffectPayload.Effects.on(Colors.RED).toByteArray()
                ),
                target = ALL_CONNECTED
            )

            EventType.CALL_RINGING -> EventRule(
                id = "call-cyan-strobe",
                trigger = EventTrigger(type),
                action = EventAction.SendEffectFrame(
                    bytes20 = LSEffectPayload.Effects.strobe(Colors.CYAN, period = 5).toByteArray()
                ),
                target = ALL_CONNECTED
            )

            EventType.CALENDAR_START -> EventRule(
                id = "cal-white-on",
                trigger = EventTrigger(type, EventFilter(calendarTitle = "Standup")),
                action = EventAction.SendEffectFrame(
                    bytes20 = LSEffectPayload.Effects.on(Colors.WHITE).toByteArray()
                ),
                target = ALL_CONNECTED
            )

            EventType.CALENDAR_END -> EventRule(
                id = "cal-end-pink-blink",
                trigger = EventTrigger(type),
                action = EventAction.SendEffectFrame(
                    bytes20 = LSEffectPayload.Effects.blink(Colors.PINK, period = 10).toByteArray()
                ),
                target = ALL_CONNECTED
            )

            EventType.CUSTOM -> EventRule(
                id = "custom-orange-breath",
                trigger = EventTrigger(type),
                action = EventAction.SendEffectFrame(
                    bytes20 = LSEffectPayload.Effects.breath(Colors.ORANGE, period = 14).toByteArray()
                ),
                target = ALL_CONNECTED
            )
        }
    }

    // --------------------------------------------------------------------------------------------
    // Notification bridge (symbol requested)
    // --------------------------------------------------------------------------------------------

    /**
     * sampleNotificationListener:
     * Shows how to provide a NotificationListener subclass that forwards notifications to the SDK.
     *
     * Register the nested [MyNotificationListener] in your AndroidManifest.
     */
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

    /** sampleEventFilterUsage: demonstrate EventFilter creation. */
    fun sampleEventFilterUsage(): EventFilter =
        EventFilter(
            smsContains = "meeting",
            phoneNumber = "+821012345678",
            calendarTitle = "Project",
            calendarLocation = "HQ"
        )

    /** sampleEventTriggerUsage: demonstrate EventTrigger creation. */
    fun sampleEventTriggerUsage(): EventTrigger =
        EventTrigger(
            type = EventType.SMS_RECEIVED,
            filter = EventFilter(smsContains = "urgent")
        )

    /** sampleSendColorAction: build EventAction.SendColorPacket. */
    fun sampleSendColorAction(): EventAction.SendColorPacket =
        EventAction.SendColorPacket(byteArrayOf(255.toByte(), 0, 0, 8))

    /** sampleSendEffectAction: build EventAction.SendEffectFrame with blink BLUE. */
    fun sampleSendEffectAction(): EventAction.SendEffectFrame =
        EventAction.SendEffectFrame(
            bytes20 = LSEffectPayload.Effects.blink(Colors.BLUE, period = 10).toByteArray()
        )

    /** samplePlayFramesAction: build EventAction.PlayFrames example (two frames). */
    fun samplePlayFramesAction(): EventAction.PlayFrames {
        val entries = listOf(
            0L to LSEffectPayload.Effects.on(Colors.WHITE).toByteArray(),
            300L to LSEffectPayload.Effects.off().toByteArray()
        )
        return EventAction.PlayFrames(entries)
    }

    /** sampleEventActionUsage: showcase all 3 action types. */
    fun sampleEventActionUsage(): List<EventAction> = listOf(
        sampleSendColorAction(),
        sampleSendEffectAction(),
        samplePlayFramesAction()
    )

    /** sampleBuildEventRule: complete EventRule definition example. */
    fun sampleBuildEventRule(): EventRule =
        EventRule(
            id = "demo-rule",
            trigger = sampleEventTriggerUsage(),
            action = sampleSendEffectAction(),
            target = ALL_CONNECTED,
            stopAfterMatch = true
        )

}
