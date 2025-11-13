package com.lightstick.events

import android.util.Log
import com.lightstick.internal.api.Facade

/**
 * Public Event manager facade.
 *
 * Responsibilities:
 * - Enable/disable the internal event pipeline (monitors/receivers/observers)
 * - Set/clear/get **global** rules (ALL_CONNECTED)
 * - Set/clear/get **device-scoped** rules (THIS_DEVICE)
 * - Snapshot of all rules (global + per-device)
 *
 * Dependency direction:
 * - Public module maps DTOs via [EventMapper] and calls internal engine through
 *   [Facade]'s *Internal* methods.
 * - The internal module does **not** depend on public DTOs.
 *
 * Threading:
 * - Lightweight registry operations. Main thread is OK.
 *
 * @since 1.0.0
 */
object EventManager {

    /**
     * Aggregated snapshot of the public registry view.
     *
     * @property globalRules Rules evaluated against **all** connected devices.
     * @property deviceRules Map of device MAC → rules evaluated **only** for that device.
     */
    data class Snapshot(
        val globalRules: List<EventRule>,
        val deviceRules: Map<String, List<EventRule>>
    )

    // --------------------------------------------------------------------------------------------
    // Lifecycle
    // --------------------------------------------------------------------------------------------

    /**
     * Enables the internal event pipeline.
     *
     * @throws IllegalStateException If the internal engine cannot be initialized.
     * @sample com.lightstick.samples.EventSamples.sampleEnableEventPipeline
     */
    @JvmStatic
    fun enable() {
        try {
            Facade.eventEnable()
        } catch (e: Exception) {
            Log.w("EventManager", "enable() failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Disables the internal event pipeline.
     *
     * @throws IllegalStateException If the internal engine cannot be shut down.
     * @sample com.lightstick.samples.EventSamples.sampleDisableEventPipeline
     */
    @JvmStatic
    fun disable() {
        try {
            Facade.eventDisable()
        } catch (e: Exception) {
            Log.w("EventManager", "disable() failed: ${e.message}", e)
            throw e
        }
    }

    // --------------------------------------------------------------------------------------------
    // Global rules (ALL_CONNECTED)
    // --------------------------------------------------------------------------------------------

    /**
     * Replaces all **global** rules with [rules]. Empty list clears them.
     *
     * @param rules Public rules to register globally.
     * @throws IllegalArgumentException If any rule is structurally invalid.
     * @throws IllegalStateException If the engine is not initialized.
     * @sample com.lightstick.samples.EventSamples.sampleSetGlobalRules
     */
    @JvmStatic
    fun setGlobalRules(rules: List<EventRule>) {
        try {
            val internal = rules.map(EventMapper::toInternalGlobal)
            Facade.eventSetGlobalRulesInternal(internal)
        } catch (e: Exception) {
            Log.w("EventManager", "setGlobalRules() failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Clears all **global** rules.
     *
     * @throws IllegalStateException If the engine is not initialized.
     * @sample com.lightstick.samples.EventSamples.sampleClearGlobalRules
     */
    @JvmStatic
    fun clearGlobalRules() {
        try {
            Facade.eventClearGlobalRulesInternal()
        } catch (e: Exception) {
            Log.w("EventManager", "clearGlobalRules() failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Returns the current **global** rules.
     *
     * @return List of global [EventRule] (may be empty).
     * @throws IllegalStateException If the engine is not initialized.
     * @sample com.lightstick.samples.EventSamples.sampleGetGlobalRules
     */
    @JvmStatic
    fun getGlobalRules(): List<EventRule> {
        return try {
            Facade.eventGetGlobalRulesInternal().map(EventMapper::fromInternalGlobal)
        } catch (e: Exception) {
            Log.w("EventManager", "getGlobalRules() failed: ${e.message}", e)
            throw e
        }
    }

    // --------------------------------------------------------------------------------------------
    // Device-scoped rules (THIS_DEVICE)
    // --------------------------------------------------------------------------------------------

    /**
     * Replaces all rules for [mac] with [rules]. Empty list clears them.
     *
     * @param mac Target device MAC.
     * @param rules Device-scoped rules.
     * @throws IllegalArgumentException If any rule is structurally invalid.
     * @throws IllegalStateException If the engine is not initialized.
     * @sample com.lightstick.samples.EventSamples.sampleSetDeviceRules
     */
    @JvmStatic
    fun setDeviceRules(mac: String, rules: List<EventRule>) {
        try {
            val internal = rules.map { EventMapper.toInternalForDevice(mac, it) }
            Facade.eventSetDeviceRulesInternal(mac, internal)
        } catch (e: Exception) {
            Log.w("EventManager", "setDeviceRules($mac) failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Clears device-scoped rules for [mac].
     *
     * @param mac Target device MAC.
     * @throws IllegalStateException If the engine is not initialized.
     * @sample com.lightstick.samples.EventSamples.sampleClearDeviceRules
     */
    @JvmStatic
    fun clearDeviceRules(mac: String) {
        try {
            Facade.eventClearDeviceRulesInternal(mac)
        } catch (e: Exception) {
            Log.w("EventManager", "clearDeviceRules($mac) failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Returns current device-scoped rules for [mac].
     *
     * @param mac Target device MAC.
     * @return List of [EventRule] (may be empty).
     * @throws IllegalStateException If the engine is not initialized.
     * @sample com.lightstick.samples.EventSamples.sampleGetDeviceRules
     */
    @JvmStatic
    fun getDeviceRules(mac: String): List<EventRule> {
        return try {
            Facade.eventGetDeviceRulesInternal(mac)
                .map { EventMapper.fromInternalForDevice(mac, it) }
        } catch (e: Exception) {
            Log.w("EventManager", "getDeviceRules($mac) failed: ${e.message}", e)
            throw e
        }
    }

    // --------------------------------------------------------------------------------------------
    // Snapshot (global + per-device)
    // --------------------------------------------------------------------------------------------

    /**
     * Returns a snapshot of all registered rules.
     *
     * @return [Snapshot] containing global rules and per-device rules.
     * @throws IllegalStateException If the engine is not initialized.
     * @sample com.lightstick.samples.EventSamples.sampleGetAllRules
     */
    @JvmStatic
    fun getAllRules(): Snapshot {
        return try {
            val global = Facade.eventGetGlobalRulesInternal().map(EventMapper::fromInternalGlobal)
            val perDevice = Facade.eventGetAllDeviceRulesInternal()
                .mapValues { (mac, list) ->
                    list.map { EventMapper.fromInternalForDevice(mac, it) }
                }
            Snapshot(globalRules = global, deviceRules = perDevice)
        } catch (e: Exception) {
            Log.w("EventManager", "getAllRules() failed: ${e.message}", e)
            throw e
        }
    }
}
