package com.lightstick.events

/**
 * Specifies the target scope for executing an action when a matching rule is triggered.
 *
 * @since 1.0.0
 */
enum class EventTarget {

    /** Apply the action to **all currently connected** devices. */
    ALL_CONNECTED,

    /** Apply the action **only to the device that registered this rule**. */
    THIS_DEVICE
}

/**
 * Describes what kind of event should trigger a rule.
 *
 * @param type The event category that triggers the rule (see [EventType]).
 *
 * @since 1.0.0
 */
data class EventTrigger(
    val type: EventType
)

/**
 * Defines an executable action that the SDK should perform when a rule matches.
 *
 * @since 1.0.0
 * @sample com.lightstick.samples.EventSamples.sampleEventActionUsage
 */
sealed interface EventAction {

    /**
     * Sends a 4-byte color packet to the target device(s).
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
     * Sends a single 20-byte LED effect frame to the target device(s).
     *
     * @param bytes20 The 20-byte array representing the effect frame.
     * @throws IllegalArgumentException If [bytes20] is not exactly 20 bytes long.
     * @since 1.0.0
     * @sample com.lightstick.samples.EventSamples.sampleSendEffectAction
     */
    data class SendEffectFrame(val bytes20: ByteArray) : EventAction {
        init {
            require(bytes20.size == 20) { "bytes20 must be exactly 20 bytes" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SendEffectFrame) return false
            return bytes20.contentEquals(other.bytes20)
        }
        override fun hashCode(): Int = bytes20.contentHashCode()
    }

    /**
     * Plays a time-sequenced list of effect frames on the target device(s).
     *
     * @param entries The ordered list of (timestampMs, frameBytes) pairs.
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
