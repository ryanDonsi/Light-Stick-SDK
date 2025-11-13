package com.lightstick.events

/**
 * Enumerates the event categories that the SDK can react to.
 *
 * This enum defines all supported event sources that can trigger
 * an [EventTrigger] and execute corresponding [EventAction]s.
 *
 * The list should remain stable for application developers.
 * Platform- or device-specific handling logic should reside
 * in the internal event monitor layer.
 *
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.EventSamples.sampleEventTypeUsage
 */
enum class EventType {

    /**
     * Triggered when an **incoming SMS** message is received.
     *
     * Typically monitored via the Android SMS broadcast receiver.
     */
    SMS_RECEIVED,

    /**
     * Triggered when the **phone is ringing** due to an incoming call.
     *
     * Typically monitored through the Telephony or CallState API.
     */
    CALL_RINGING,

    /**
     * Triggered when a **calendar event starts** or is about to start soon.
     *
     * The exact timing is managed by the internal CalendarMonitor.
     */
    CALENDAR_START,

    /**
     * Triggered when a **calendar event ends**.
     *
     * Indicates the completion of a meeting or scheduled event.
     */
    CALENDAR_END,

    /**
     * Reserved for **custom application-defined** triggers.
     *
     * This type is intended for future extension and may be used
     * by app developers to define app-specific signals.
     */
    CUSTOM
}
