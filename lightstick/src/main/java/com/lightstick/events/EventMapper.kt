package com.lightstick.events

import com.lightstick.events.EventAction.*
import com.lightstick.internal.event.EventType as InternalEventType
import com.lightstick.internal.event.InternalAction
import com.lightstick.internal.event.InternalFilter
import com.lightstick.internal.event.InternalRule
import com.lightstick.internal.event.InternalTarget
import com.lightstick.internal.event.InternalTrigger

/**
 * Maps public Event DTOs to internal event-engine models and back.
 *
 * Design:
 * - The internal module MUST NOT depend on public DTOs.
 * - Therefore, all conversions live in this public-side mapper.
 *
 * Threading: Stateless and cheap; any thread is OK.
 *
 * @since 1.0.0
 */
object EventMapper {

    // =============================================================================================
    // PUBLIC -> INTERNAL
    // =============================================================================================

    /**
     * Converts a public [EventRule] into an internal rule targeted to **ALL_CONNECTED**.
     *
     * @param publicRule Public rule.
     * @return Internal representation with target normalized to [InternalTarget.All].
     * @throws IllegalArgumentException If [publicRule] is structurally invalid.
     * @sample com.lightstick.samples.EventSamples.sampleMapGlobalRule
     */
    @JvmStatic
    fun toInternalGlobal(publicRule: EventRule): InternalRule {
        val trigger = InternalTrigger(
            type = publicRule.trigger.type.toInternal(),
            filter = publicRule.trigger.filter.toInternal()
        )
        val action = publicRule.action.toInternal()
        return InternalRule(
            id = publicRule.id,
            trigger = trigger,
            action = action,
            target = InternalTarget.All,
            stopAfterMatch = publicRule.stopAfterMatch
        )
    }

    /**
     * Converts a public [EventRule] into an internal rule targeted to a specific device [mac].
     *
     * @param mac Device MAC for THIS_DEVICE scope.
     * @param publicRule Public rule.
     * @return Internal representation with target = [InternalTarget.Address].
     * @throws IllegalArgumentException If [publicRule] is structurally invalid.
     * @sample com.lightstick.samples.EventSamples.sampleMapDeviceRule
     */
    @JvmStatic
    fun toInternalForDevice(mac: String, publicRule: EventRule): InternalRule {
        val trigger = InternalTrigger(
            type = publicRule.trigger.type.toInternal(),
            filter = publicRule.trigger.filter.toInternal()
        )
        val action = publicRule.action.toInternal()
        return InternalRule(
            id = publicRule.id,
            trigger = trigger,
            action = action,
            target = InternalTarget.Address(mac),
            stopAfterMatch = publicRule.stopAfterMatch
        )
    }

    // =============================================================================================
    // INTERNAL -> PUBLIC
    // =============================================================================================

    /**
     * Converts an internal **global** rule back to a public [EventRule].
     * The returned rule's target is normalized to [EventTarget.ALL_CONNECTED].
     *
     * @param internalRule Internal rule.
     * @return Public [EventRule] for ALL_CONNECTED scope.
     * @sample com.lightstick.samples.EventSamples.sampleMapFromInternalGlobal
     */
    @JvmStatic
    fun fromInternalGlobal(internalRule: InternalRule): EventRule {
        val trigger = EventTrigger(
            type = internalRule.trigger.type.toPublic(),
            filter = internalRule.trigger.filter.toPublic()
        )
        val action = internalRule.action.toPublic()
        return EventRule(
            id = internalRule.id,
            trigger = trigger,
            action = action,
            target = EventTarget.ALL_CONNECTED,
            stopAfterMatch = internalRule.stopAfterMatch
        )
    }

    /**
     * Converts an internal **device-scoped** rule back to a public [EventRule].
     * The returned rule's target is normalized to [EventTarget.THIS_DEVICE].
     *
     * @param mac Device MAC (informational).
     * @param internalRule Internal rule.
     * @return Public [EventRule] for THIS_DEVICE scope.
     * @sample com.lightstick.samples.EventSamples.sampleMapFromInternalDevice
     */
    @JvmStatic
    fun fromInternalForDevice(mac: String, internalRule: InternalRule): EventRule {
        val trigger = EventTrigger(
            type = internalRule.trigger.type.toPublic(),
            filter = internalRule.trigger.filter.toPublic()
        )
        val action = internalRule.action.toPublic()
        return EventRule(
            id = internalRule.id,
            trigger = trigger,
            action = action,
            target = EventTarget.THIS_DEVICE,
            stopAfterMatch = internalRule.stopAfterMatch
        )
    }

    // =============================================================================================
    // SMALL HELPERS
    // =============================================================================================

    private fun EventType.toInternal(): InternalEventType = when (this) {
        EventType.SMS_RECEIVED   -> InternalEventType.SMS_RECEIVED
        EventType.CALL_RINGING   -> InternalEventType.CALL_RINGING
        EventType.CALENDAR_START -> InternalEventType.CALENDAR_START
        EventType.CALENDAR_END   -> InternalEventType.CALENDAR_END
        EventType.CUSTOM         -> InternalEventType.CUSTOM
    }

    private fun InternalEventType.toPublic(): EventType = when (this) {
        InternalEventType.SMS_RECEIVED   -> EventType.SMS_RECEIVED
        InternalEventType.CALL_RINGING   -> EventType.CALL_RINGING
        InternalEventType.CALL_ACTIVE    -> EventType.CUSTOM  // internal-only
        InternalEventType.CALL_IDLE      -> EventType.CUSTOM  // internal-only
        InternalEventType.CALENDAR_START -> EventType.CALENDAR_START
        InternalEventType.CALENDAR_END   -> EventType.CALENDAR_END
        InternalEventType.CUSTOM         -> EventType.CUSTOM
    }

    private fun EventFilter.toInternal(): InternalFilter =
        InternalFilter(
            smsContains = smsContains,
            phoneNumber = phoneNumber,
            calendarTitle = calendarTitle,
            calendarLocation = calendarLocation
        )

    private fun InternalFilter.toPublic(): EventFilter =
        EventFilter(
            smsContains = smsContains,
            phoneNumber = phoneNumber,
            calendarTitle = calendarTitle,
            calendarLocation = calendarLocation
        )

    private fun EventAction.toInternal(): InternalAction = when (this) {
        is SendColorPacket -> InternalAction.SendColorPacket(packet4)
        is SendEffectFrame -> InternalAction.SendEffectFrame(bytes16)
        is PlayFrames      -> InternalAction.PlayFrames(entries)
    }

    private fun InternalAction.toPublic(): EventAction = when (this) {
        is InternalAction.SendColorPacket -> SendColorPacket(bytes4)
        is InternalAction.SendEffectFrame -> SendEffectFrame(bytes16)
        is InternalAction.PlayFrames      -> PlayFrames(entries)
    }
}
