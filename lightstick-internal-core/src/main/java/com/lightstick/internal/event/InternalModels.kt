package com.lightstick.internal.event

/**
 * Core data models used by the internal event engine.
 */

enum class EventType {
    /** App-defined custom event. */
    CUSTOM
}

/** Trigger used by internal rules. */
data class InternalTrigger(
    val type: EventType
)

/** Execution target (all connected devices or specific one). */
sealed interface InternalTarget {
    /** Apply action to all connected devices. */
    data object All : InternalTarget
    /** Apply action only to this address. */
    data class Address(val mac: String) : InternalTarget
}

/** BLE-level actions. */
sealed interface InternalAction {
    data class SendColorPacket(val bytes4: ByteArray) : InternalAction {
        override fun equals(other: Any?): Boolean {
            return other is SendColorPacket && bytes4.contentEquals(other.bytes4)
        }
        override fun hashCode(): Int = bytes4.contentHashCode()
    }

    data class SendEffectFrame(val bytes20: ByteArray) : InternalAction {
        override fun equals(other: Any?): Boolean {
            return other is SendEffectFrame && bytes20.contentEquals(other.bytes20)
        }
        override fun hashCode(): Int = bytes20.contentHashCode()
    }

    data class PlayFrames(val entries: List<Pair<Long, ByteArray>>) : InternalAction
}

/** Full rule structure used by EventBridge. */
data class InternalRule(
    val id: String? = null,
    val trigger: InternalTrigger,
    val action: InternalAction,
    val target: InternalTarget,
    val stopAfterMatch: Boolean = true
)

/** Event envelope delivered to EventBridge. */
data class InternalEvent(
    val type: EventType
)
