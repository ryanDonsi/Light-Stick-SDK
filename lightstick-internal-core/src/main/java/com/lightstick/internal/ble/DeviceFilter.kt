package com.lightstick.internal.ble

/**
 * Internal implementation of device filtering logic.
 *
 * Uses primitive types only to avoid depending on public module.
 * The matches() method is used internally by SDK to check if devices pass filter criteria.
 *
 * Note: Class is public for mapper access, but constructor is internal to prevent
 * direct instantiation from outside the module.
 */
class DeviceFilter internal constructor(
    private val predicate: (String, String?, Int?) -> Boolean
) {

    /**
     * Checks if a device matches this filter criteria.
     *
     * This method is used internally by SDK components (Facade, DeviceStateManager)
     * to determine whether a device should be included in operations.
     *
     * @param mac Device MAC address.
     * @param name Device name (nullable).
     * @param rssi Device RSSI (nullable).
     * @return `true` if the device matches the filter criteria; `false` otherwise.
     */
    internal fun matches(mac: String, name: String?, rssi: Int? = null): Boolean {
        return predicate(mac, name, rssi)
    }

    /**
     * Combines this filter with another using AND logic.
     *
     * @param other The filter to combine with this one.
     * @return A new filter representing the AND combination.
     */
    fun and(other: DeviceFilter): DeviceFilter {
        return DeviceFilter { mac, name, rssi ->
            this.matches(mac, name, rssi) && other.matches(mac, name, rssi)
        }
    }

    /**
     * Combines this filter with another using OR logic.
     *
     * @param other The filter to combine with this one.
     * @return A new filter representing the OR combination.
     */
    fun or(other: DeviceFilter): DeviceFilter {
        return DeviceFilter { mac, name, rssi ->
            this.matches(mac, name, rssi) || other.matches(mac, name, rssi)
        }
    }

    /**
     * Inverts this filter.
     *
     * @return A new filter representing the NOT of this filter.
     */
    fun not(): DeviceFilter {
        return DeviceFilter { mac, name, rssi ->
            !this.matches(mac, name, rssi)
        }
    }

    companion object {

        /**
         * Creates a filter that matches devices by name pattern.
         */
        fun byName(
            pattern: String,
            mode: MatchMode,
            ignoreCase: Boolean
        ): DeviceFilter {
            return DeviceFilter { _, name, _ ->
                val deviceName = name ?: return@DeviceFilter false
                when (mode) {
                    MatchMode.CONTAINS ->
                        deviceName.contains(pattern, ignoreCase)
                    MatchMode.STARTS_WITH ->
                        deviceName.startsWith(pattern, ignoreCase)
                    MatchMode.ENDS_WITH ->
                        deviceName.endsWith(pattern, ignoreCase)
                    MatchMode.EQUALS ->
                        deviceName.equals(pattern, ignoreCase)
                    MatchMode.REGEX ->
                        Regex(pattern).matches(deviceName)
                }
            }
        }

        /**
         * Creates a filter that matches devices by MAC address prefix (OUI).
         *
         * All MAC address formats (with/without colons, any case) are normalized
         * for comparison.
         */
        fun byMacPrefix(prefix: String): DeviceFilter {
            val normalizedPrefix = prefix.replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
            return DeviceFilter { mac, _, _ ->
                val normalizedMac = mac.replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
                normalizedMac.startsWith(normalizedPrefix)
            }
        }

        /**
         * Creates a filter that matches a specific MAC address exactly.
         */
        fun byMacAddress(macAddress: String): DeviceFilter {
            val normalizedTarget = macAddress.replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
            return DeviceFilter { mac, _, _ ->
                val normalizedMac = mac.replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
                normalizedMac == normalizedTarget
            }
        }

        /**
         * Creates a filter that matches devices by minimum RSSI.
         */
        fun byMinRssi(minRssi: Int): DeviceFilter {
            return DeviceFilter { _, _, rssi ->
                (rssi ?: Int.MIN_VALUE) >= minRssi
            }
        }

        /**
         * Creates a filter that matches devices within RSSI range.
         */
        fun byRssiRange(minRssi: Int, maxRssi: Int): DeviceFilter {
            return DeviceFilter { _, _, rssi ->
                val deviceRssi = rssi ?: Int.MIN_VALUE
                deviceRssi in minRssi..maxRssi
            }
        }

        /**
         * Creates a custom filter with user-defined logic.
         */
        fun custom(predicate: (String, String?, Int?) -> Boolean): DeviceFilter {
            return DeviceFilter(predicate)
        }

        /**
         * Combines multiple filters using AND logic.
         */
        fun allOf(vararg filters: DeviceFilter): DeviceFilter {
            return DeviceFilter { mac, name, rssi ->
                filters.all { it.matches(mac, name, rssi) }
            }
        }

        /**
         * Combines multiple filters using OR logic.
         */
        fun anyOf(vararg filters: DeviceFilter): DeviceFilter {
            return DeviceFilter { mac, name, rssi ->
                filters.any { it.matches(mac, name, rssi) }
            }
        }

        /**
         * Creates a filter that accepts all devices.
         */
        fun acceptAll(): DeviceFilter {
            return DeviceFilter { _, _, _ -> true }
        }

        /**
         * Creates a filter that rejects all devices.
         */
        fun rejectAll(): DeviceFilter {
            return DeviceFilter { _, _, _ -> false }
        }
    }

    /**
     * Pattern matching modes for name-based filtering.
     */
    enum class MatchMode {
        CONTAINS,
        STARTS_WITH,
        ENDS_WITH,
        EQUALS,
        REGEX
    }
}