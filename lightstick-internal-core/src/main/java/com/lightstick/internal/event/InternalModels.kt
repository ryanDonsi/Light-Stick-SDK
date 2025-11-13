package com.lightstick.internal.event

/**
 * Core data models used by the internal event engine.
 */

enum class EventType {
    /** Incoming SMS received. */
    SMS_RECEIVED,

    /** Phone is ringing (incoming call). */
    CALL_RINGING,

    /** Call is now active (off-hook, in-call). */
    CALL_ACTIVE,   // ✅ 추가

    /** Call ended or returned to idle state. */
    CALL_IDLE,     // ✅ 추가

    /** Calendar event started (or becomes active). */
    CALENDAR_START,

    /** Calendar event ended (or becomes inactive). */
    CALENDAR_END,

    /** App-defined custom event. */
    CUSTOM
}

/** Optional filters for trigger matching. */
data class InternalFilter(
    val smsContains: String? = null,
    val phoneNumber: String? = null,
    val calendarTitle: String? = null,
    val calendarLocation: String? = null
)

/** Trigger used by internal rules. */
data class InternalTrigger(
    val type: EventType,
    val filter: InternalFilter = InternalFilter()
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

    data class SendEffectFrame(val bytes16: ByteArray) : InternalAction {
        override fun equals(other: Any?): Boolean {
            return other is SendEffectFrame && bytes16.contentEquals(other.bytes16)
        }
        override fun hashCode(): Int = bytes16.contentHashCode()
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

/** Normalized event data payload delivered by EventRouter. */
data class InternalPayload(
    val smsBody: String? = null,
    val phoneNumber: String? = null,
    val calendarTitle: String? = null,
    val calendarLocation: String? = null
)

/** Event envelope delivered to EventBridge. */
data class InternalEvent(
    val type: EventType,
    val payload: InternalPayload = InternalPayload()
)
