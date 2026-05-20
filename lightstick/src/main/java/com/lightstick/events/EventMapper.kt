package com.lightstick.events

import com.lightstick.events.EventAction.*
import com.lightstick.internal.event.EventType as InternalEventType
import com.lightstick.internal.event.InternalAction
import com.lightstick.internal.event.InternalRule
import com.lightstick.internal.event.InternalTarget
import com.lightstick.internal.event.InternalTrigger

/**
 * Maps public Event DTOs to internal event-engine models and back.
 *
 * The internal module MUST NOT depend on public DTOs.
 * Therefore, all conversions live in this public-side mapper.
 *
 * @since 1.0.0
 */
object EventMapper {

    // =============================================================================================
    // PUBLIC -> INTERNAL
    // =============================================================================================

    @JvmStatic
    fun toInternalGlobal(publicRule: EventRule): InternalRule = InternalRule(
        id = publicRule.id,
        trigger = InternalTrigger(type = publicRule.trigger.type.toInternal()),
        action = publicRule.action.toInternal(),
        target = InternalTarget.All,
        stopAfterMatch = publicRule.stopAfterMatch
    )

    @JvmStatic
    fun toInternalForDevice(mac: String, publicRule: EventRule): InternalRule = InternalRule(
        id = publicRule.id,
        trigger = InternalTrigger(type = publicRule.trigger.type.toInternal()),
        action = publicRule.action.toInternal(),
        target = InternalTarget.Address(mac),
        stopAfterMatch = publicRule.stopAfterMatch
    )

    // =============================================================================================
    // INTERNAL -> PUBLIC
    // =============================================================================================

    @JvmStatic
    fun fromInternalGlobal(internalRule: InternalRule): EventRule = EventRule(
        id = internalRule.id,
        trigger = EventTrigger(type = internalRule.trigger.type.toPublic()),
        action = internalRule.action.toPublic(),
        target = EventTarget.ALL_CONNECTED,
        stopAfterMatch = internalRule.stopAfterMatch
    )

    @JvmStatic
    fun fromInternalForDevice(mac: String, internalRule: InternalRule): EventRule = EventRule(
        id = internalRule.id,
        trigger = EventTrigger(type = internalRule.trigger.type.toPublic()),
        action = internalRule.action.toPublic(),
        target = EventTarget.THIS_DEVICE,
        stopAfterMatch = internalRule.stopAfterMatch
    )

    // =============================================================================================
    // HELPERS
    // =============================================================================================

    private fun EventType.toInternal(): InternalEventType = when (this) {
        EventType.CUSTOM -> InternalEventType.CUSTOM
    }

    private fun InternalEventType.toPublic(): EventType = when (this) {
        InternalEventType.CUSTOM -> EventType.CUSTOM
    }

    private fun EventAction.toInternal(): InternalAction = when (this) {
        is SendColorPacket -> InternalAction.SendColorPacket(packet4)
        is SendEffectFrame -> InternalAction.SendEffectFrame(bytes20)
        is PlayFrames      -> InternalAction.PlayFrames(entries)
    }

    private fun InternalAction.toPublic(): EventAction = when (this) {
        is InternalAction.SendColorPacket -> SendColorPacket(bytes4)
        is InternalAction.SendEffectFrame -> SendEffectFrame(bytes20)
        is InternalAction.PlayFrames      -> PlayFrames(entries)
    }
}
