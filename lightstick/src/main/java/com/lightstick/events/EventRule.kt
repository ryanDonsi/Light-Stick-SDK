package com.lightstick.events

/**
 * Specifies the target scope for executing an action when a matching rule is triggered.
 *
 * @since 1.0.0
 * @sample com.lightstick.samples.EventSamples.sampleEventTypeUsage
 */
enum class EventTarget {

    /** Apply the action to **all currently connected** devices. */
    ALL_CONNECTED,

    /** Apply the action **only to the device that registered this rule**. */
    THIS_DEVICE
}

/**
 * Defines optional filter fields that refine an [EventTrigger].
 *
 * The internal event engine determines how these filters are interpreted.
 * When a field is `null`, it is ignored. Multiple fields may be combined for complex matches.
 *
 * @param smsContains Optional substring to match within an incoming SMS body.
 * @param phoneNumber Optional phone number to match for SMS or call events.
 * @param calendarTitle Optional substring to match within calendar event titles.
 * @param calendarLocation Optional substring to match within calendar locations.
 *
 * @since 1.0.0
 * @sample com.lightstick.samples.EventSamples.sampleEventFilterUsage
 */
data class EventFilter(
    val smsContains: String? = null,
    val phoneNumber: String? = null,
    val calendarTitle: String? = null,
    val calendarLocation: String? = null
)

/**
 * Describes what kind of event should trigger a rule and what filter applies to it.
 *
 * @param type The event category that triggers the rule (see [EventType]).
 * @param filter Optional [EventFilter] for additional matching conditions.
 *
 * @since 1.0.0
 * @sample com.lightstick.samples.EventSamples.sampleEventTriggerUsage
 */
data class EventTrigger(
    val type: EventType,
    val filter: EventFilter = EventFilter()
)

/**
 * Defines an executable action that the SDK should perform when a rule matches.
 *
 * These actions correspond to low-level BLE operations such as sending
 * color packets, single effect frames, or time-sequenced playback lists.
 *
 * @since 1.0.0
 * @sample com.lightstick.samples.EventSamples.sampleEventActionUsage
 */
sealed interface EventAction {

    /**
     * Sends a 4-byte color packet to the target device(s).
     *
     * Each byte corresponds to firmware-defined fields, typically:
     * `R`, `G`, `B`, and `Transition`.
     *
     * @param packet4 The 4-byte array to send.
     * @throws IllegalArgumentException If [packet4] is not exactly 4 bytes long.
     * @since 1.0.0
     * @sample com.lightstick.samples.EventSamples.sampleSendColorAction
     */
    data class SendColorPacket(val packet4: ByteArray) : EventAction {
        init {
            require(packet4.size == 4) { "packet4 must be exactly 4 bytes" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SendColorPacket) return false
            return packet4.contentEquals(other.packet4)
        }

        override fun hashCode(): Int = packet4.contentHashCode()
    }

    /**
     * Sends a single 16-byte LED effect frame to the target device(s).
     *
     * This corresponds to one LED effect payload as defined by the firmware protocol.
     *
     * @param bytes16 The 16-byte array representing the effect frame.
     * @throws IllegalArgumentException If [bytes16] is not exactly 16 bytes long.
     * @since 1.0.0
     * @sample com.lightstick.samples.EventSamples.sampleSendEffectAction
     */
    data class SendEffectFrame(val bytes16: ByteArray) : EventAction {
        init {
            require(bytes16.size == 16) { "bytes16 must be exactly 16 bytes" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SendEffectFrame) return false
            return bytes16.contentEquals(other.bytes16)
        }

        override fun hashCode(): Int = bytes16.contentHashCode()
    }

    /**
     * Plays a time-sequenced list of effect frames on the target device(s).
     *
     * Each element is a pair: `(timestampMs, frameBytes)`, where `frameBytes`
     * is a 16-byte payload representing a single effect frame.
     *
     * @param entries The ordered list of (timestamp, frame) pairs.
     * @since 1.0.0
     * @sample com.lightstick.samples.EventSamples.samplePlayFramesAction
     */
    data class PlayFrames(val entries: List<Pair<Long, ByteArray>>) : EventAction
}

/**
 * Represents a complete rule that binds a [trigger] to an [action].
 *
 * When the specified [trigger] condition matches an incoming event,
 * the [action] is executed within the defined [target] scope.
 * If [stopAfterMatch] is true, further rule evaluation stops.
 *
 * @param id Optional rule identifier for tracking or replacement.
 * @param trigger The triggering condition to evaluate.
 * @param action The action to execute when matched.
 * @param target The device scope for applying the action.
 * @param stopAfterMatch Whether to stop rule evaluation after this match.
 *
 * @since 1.0.0
 * @sample com.lightstick.samples.EventSamples.sampleBuildEventRule
 */
data class EventRule(
    val id: String? = null,
    val trigger: EventTrigger,
    val action: EventAction,
    val target: EventTarget = EventTarget.ALL_CONNECTED,
    val stopAfterMatch: Boolean = true
)
