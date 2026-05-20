package com.lightstick.events

/**
 * Enumerates the event categories that the SDK can react to.
 *
 * @since 1.0.0
 * @sample com.lightstick.samples.EventSamples.sampleEventTypeUsage
 */
enum class EventType {

    /**
     * Reserved for **custom application-defined** triggers.
     *
     * Use this type to define app-specific signals and wire them
     * to BLE actions via [EventRule].
     */
    CUSTOM
}
