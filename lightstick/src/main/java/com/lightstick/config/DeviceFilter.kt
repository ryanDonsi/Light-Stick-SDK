package com.lightstick.config

import com.lightstick.device.Device

/**
 * Device filtering configuration for controlling which BLE devices are eligible
 * for SDK operations.
 *
 * This data class defines filtering rules that are applied globally across all SDK operations:
 * - System connection restoration (on app restart)
 * - Scan result callbacks
 * - Connection attempt validation
 * - Bonded device list retrieval
 *
 * Filters are created using the companion object factory methods and can be combined
 * using logical operators ([and], [or], [not]) to build complex filtering rules.
 *
 * Usage examples:
 * ```
 * // Name-based filtering
 * val filter = DeviceFilter.byName("LS")
 * LSBluetooth.initialize(context, deviceFilter = filter)
 *
 * // MAC address OUI filtering (manufacturer-specific)
 * val filter = DeviceFilter.byMacPrefix("AA:BB:CC")
 * LSBluetooth.initialize(context, deviceFilter = filter)
 *
 * // Combined filters with signal strength requirement
 * val filter = DeviceFilter.byName("LS")
 *     .and(DeviceFilter.byMacPrefix("AA:BB:CC"))
 *     .and(DeviceFilter.byMinRssi(-70))
 * LSBluetooth.initialize(context, deviceFilter = filter)
 *
 * // Multiple product lines
 * val filter = DeviceFilter.byMacPrefixes("AA:BB:CC", "DD:EE:FF", "11:22:33")
 * LSBluetooth.initialize(context, deviceFilter = filter)
 *
 * // Custom logic
 * val filter = DeviceFilter.custom { device ->
 *     device.name?.contains("LS") == true &&
 *     device.mac.startsWith("AA:BB:CC") &&
 *     (device.rssi ?: -100) > -70
 * }
 * LSBluetooth.initialize(context, deviceFilter = filter)
 * ```
 *
 * @property type The type of filter operation.
 * @property pattern Pattern string for name/MAC filters (nullable).
 * @property matchMode Match mode for name-based filters (nullable).
 * @property ignoreCase Whether to ignore case for name matching (default: true).
 * @property minRssi Minimum RSSI threshold for signal strength filters (nullable).
 * @property maxRssi Maximum RSSI threshold for signal strength filters (nullable).
 * @property customPredicate Custom filter function for advanced use cases (nullable).
 * @property combinedFilters List of sub-filters for AND/OR/NOT operations (nullable).
 *
 * @since 1.4.1
 * @see MatchMode
 * @see FilterType
 */
data class DeviceFilter internal constructor(
    val type: FilterType,
    val pattern: String? = null,
    val matchMode: MatchMode? = null,
    val ignoreCase: Boolean = true,
    val minRssi: Int? = null,
    val maxRssi: Int? = null,
    val customPredicate: ((Device) -> Boolean)? = null,
    val combinedFilters: List<DeviceFilter>? = null
) {

    companion object {

        // ============================================================================================
        // Name-based Filters
        // ============================================================================================

        /**
         * Creates a filter that matches devices by name pattern.
         *
         * This is the most common filtering method for identifying devices by their
         * advertised Bluetooth name.
         *
         * Examples:
         * ```
         * // Match any device containing "LS"
         * DeviceFilter.byName("LS")
         *
         * // Match devices starting with "LS-PRO"
         * DeviceFilter.byName("LS-PRO", MatchMode.STARTS_WITH)
         *
         * // Match devices ending with "-2024"
         * DeviceFilter.byName("-2024", MatchMode.ENDS_WITH)
         *
         * // Case-sensitive matching
         * DeviceFilter.byName("LS-PRO", MatchMode.EQUALS, ignoreCase = false)
         * ```
         *
         * @param pattern The pattern to match against device names.
         * @param mode The matching mode to use (default: [MatchMode.CONTAINS]).
         * @param ignoreCase Whether to ignore case when matching (default: `true`).
         * @return A filter that passes devices whose names match the pattern.
         *
         * @see MatchMode
         */
        @JvmStatic
        @JvmOverloads
        fun byName(
            pattern: String,
            mode: MatchMode = MatchMode.CONTAINS,
            ignoreCase: Boolean = true
        ): DeviceFilter {
            return DeviceFilter(
                type = FilterType.NAME,
                pattern = pattern,
                matchMode = mode,
                ignoreCase = ignoreCase
            )
        }

        // ============================================================================================
        // MAC Address-based Filters
        // ============================================================================================

        /**
         * Creates a filter that matches devices by MAC address prefix (OUI).
         *
         * This is useful for filtering by manufacturer or product line, as the first
         * 3 bytes of a MAC address (OUI - Organizationally Unique Identifier) are
         * assigned by IEEE to specific manufacturers.
         *
         * The prefix can be specified with or without colons and in any case.
         * All formats are normalized internally for comparison.
         *
         * Examples:
         * ```
         * // Match manufacturer by OUI
         * DeviceFilter.byMacPrefix("AA:BB:CC")  // Matches AA:BB:CC:xx:xx:xx
         *
         * // Flexible formatting
         * DeviceFilter.byMacPrefix("AA:BB")     // Matches AA:BB:xx:xx:xx:xx
         * DeviceFilter.byMacPrefix("AABBCC")    // Same as AA:BB:CC (auto-formatted)
         * ```
         *
         * @param prefix MAC address prefix (with or without colons).
         * @return A filter that passes devices whose MAC addresses start with the prefix.
         */
        @JvmStatic
        fun byMacPrefix(prefix: String): DeviceFilter {
            return DeviceFilter(
                type = FilterType.MAC_PREFIX,
                pattern = prefix
            )
        }

        /**
         * Creates a filter that matches devices by any of the specified MAC address prefixes.
         *
         * This is useful when you need to support multiple manufacturers or product lines.
         *
         * Example:
         * ```
         * // Support multiple product lines
         * DeviceFilter.byMacPrefixes("AA:BB:CC", "DD:EE:FF", "11:22:33")
         * ```
         *
         * @param prefixes Variable number of MAC address prefixes.
         * @return A filter that passes devices whose MAC addresses start with any of the prefixes.
         */
        @JvmStatic
        fun byMacPrefixes(vararg prefixes: String): DeviceFilter {
            val filters = prefixes.map { byMacPrefix(it) }
            return DeviceFilter(
                type = FilterType.OR,
                combinedFilters = filters
            )
        }

        /**
         * Creates a filter that matches a specific MAC address exactly.
         *
         * This is useful for targeting a single known device.
         *
         * Example:
         * ```
         * DeviceFilter.byMacAddress("AA:BB:CC:DD:EE:FF")
         * ```
         *
         * @param macAddress The exact MAC address to match (with or without colons).
         * @return A filter that passes only the device with the specified MAC address.
         */
        @JvmStatic
        fun byMacAddress(macAddress: String): DeviceFilter {
            return DeviceFilter(
                type = FilterType.MAC_ADDRESS,
                pattern = macAddress
            )
        }

        // ============================================================================================
        // RSSI-based Filters
        // ============================================================================================

        /**
         * Creates a filter that matches devices by minimum RSSI (signal strength).
         *
         * This is useful for filtering out devices that are too far away or have weak signals.
         * RSSI values are typically negative, with values closer to 0 indicating stronger signals.
         *
         * Common RSSI ranges:
         * - `-30 to -50 dBm`: Excellent signal
         * - `-50 to -70 dBm`: Good signal
         * - `-70 to -90 dBm`: Weak signal
         * - Below `-90 dBm`: Very weak signal
         *
         * Example:
         * ```
         * // Only devices with good signal strength
         * DeviceFilter.byMinRssi(-70)
         * ```
         *
         * @param minRssi Minimum RSSI value (e.g., `-70`).
         * @return A filter that passes devices with RSSI at or above the minimum.
         */
        @JvmStatic
        fun byMinRssi(minRssi: Int): DeviceFilter {
            return DeviceFilter(
                type = FilterType.MIN_RSSI,
                minRssi = minRssi
            )
        }

        /**
         * Creates a filter that matches devices within an RSSI range.
         *
         * This is useful for selecting devices within a specific distance range.
         *
         * Example:
         * ```
         * // Medium-distance devices only
         * DeviceFilter.byRssiRange(-70, -50)
         * ```
         *
         * @param minRssi Minimum RSSI value (inclusive).
         * @param maxRssi Maximum RSSI value (inclusive).
         * @return A filter that passes devices with RSSI within the range.
         */
        @JvmStatic
        fun byRssiRange(minRssi: Int, maxRssi: Int): DeviceFilter {
            return DeviceFilter(
                type = FilterType.RSSI_RANGE,
                minRssi = minRssi,
                maxRssi = maxRssi
            )
        }

        // ============================================================================================
        // Custom & Special Filters
        // ============================================================================================

        /**
         * Creates a custom filter with user-defined logic.
         *
         * This provides maximum flexibility for complex filtering requirements that
         * cannot be expressed using the built-in filter methods.
         *
         * Example:
         * ```
         * val filter = DeviceFilter.custom { device ->
         *     val hasCorrectName = device.name?.contains("LS") == true
         *     val hasCorrectOui = device.mac.startsWith("AA:BB:CC")
         *     val hasGoodSignal = (device.rssi ?: -100) > -70
         *     val notTestDevice = device.name?.contains("TEST") != true
         *
         *     hasCorrectName && hasCorrectOui && hasGoodSignal && notTestDevice
         * }
         * ```
         *
         * @param predicate A function that returns `true` if the device should pass the filter.
         * @return A filter using the custom predicate.
         */
        @JvmStatic
        fun custom(predicate: (Device) -> Boolean): DeviceFilter {
            return DeviceFilter(
                type = FilterType.CUSTOM,
                customPredicate = predicate
            )
        }

        /**
         * Creates a filter that accepts all devices.
         *
         * This is equivalent to not having a filter at all and is provided for
         * explicit intent or dynamic filter selection scenarios.
         *
         * @return A filter that passes all devices.
         */
        @JvmStatic
        fun acceptAll(): DeviceFilter {
            return DeviceFilter(type = FilterType.ACCEPT_ALL)
        }

        /**
         * Creates a filter that rejects all devices.
         *
         * This is useful for temporarily disabling all device operations while
         * keeping the SDK initialized.
         *
         * @return A filter that rejects all devices.
         */
        @JvmStatic
        fun rejectAll(): DeviceFilter {
            return DeviceFilter(type = FilterType.REJECT_ALL)
        }
    }

    // ============================================================================================
    // Combination Methods
    // ============================================================================================

    /**
     * Combines this filter with another using AND logic.
     *
     * The resulting filter passes only if both this filter AND the other filter pass.
     *
     * Example:
     * ```
     * val filter = DeviceFilter.byName("LS")
     *     .and(DeviceFilter.byMacPrefix("AA:BB:CC"))
     * // Only devices with "LS" in name AND MAC starting with AA:BB:CC
     * ```
     *
     * @param other The filter to combine with this one.
     * @return A new filter representing the AND combination.
     */
    fun and(other: DeviceFilter): DeviceFilter {
        return DeviceFilter(
            type = FilterType.AND,
            combinedFilters = listOf(this, other)
        )
    }

    /**
     * Combines this filter with another using OR logic.
     *
     * The resulting filter passes if either this filter OR the other filter passes.
     *
     * Example:
     * ```
     * val filter = DeviceFilter.byName("LS-PRO")
     *     .or(DeviceFilter.byName("LS-LITE"))
     * // Devices with either "LS-PRO" or "LS-LITE" in name
     * ```
     *
     * @param other The filter to combine with this one.
     * @return A new filter representing the OR combination.
     */
    fun or(other: DeviceFilter): DeviceFilter {
        return DeviceFilter(
            type = FilterType.OR,
            combinedFilters = listOf(this, other)
        )
    }

    /**
     * Inverts this filter.
     *
     * The resulting filter passes only if this filter does NOT pass.
     *
     * Example:
     * ```
     * val filter = DeviceFilter.byName("TEST").not()
     * // All devices except those with "TEST" in name
     * ```
     *
     * @return A new filter representing the NOT of this filter.
     */
    fun not(): DeviceFilter {
        return DeviceFilter(
            type = FilterType.NOT,
            combinedFilters = listOf(this)
        )
    }

    // ============================================================================================
    // Enumerations
    // ============================================================================================

    /**
     * Filter type enumeration.
     *
     * Defines the type of filtering operation to perform.
     */
    enum class FilterType {
        /** Name pattern matching */
        NAME,
        /** MAC address prefix matching (OUI) */
        MAC_PREFIX,
        /** Exact MAC address matching */
        MAC_ADDRESS,
        /** Minimum RSSI threshold */
        MIN_RSSI,
        /** RSSI range matching */
        RSSI_RANGE,
        /** Custom predicate function */
        CUSTOM,
        /** Logical AND combination */
        AND,
        /** Logical OR combination */
        OR,
        /** Logical NOT inversion */
        NOT,
        /** Accept all devices */
        ACCEPT_ALL,
        /** Reject all devices */
        REJECT_ALL
    }

    /**
     * Pattern matching modes for name-based filtering.
     *
     * These modes control how the pattern string is matched against device names.
     */
    enum class MatchMode {
        /**
         * The device name contains the pattern anywhere within it.
         *
         * Example: Pattern `"LS"` matches `"LS-Device"`, `"My-LS-Pro"`, `"Device-LS-Mini"`
         */
        CONTAINS,

        /**
         * The device name starts with the pattern.
         *
         * Example: Pattern `"LS"` matches `"LS-Device"`, `"LS-Pro"` but not `"My-LS"`
         */
        STARTS_WITH,

        /**
         * The device name ends with the pattern.
         *
         * Example: Pattern `"LS"` matches `"Device-LS"`, `"Pro-LS"` but not `"LS-Device"`
         */
        ENDS_WITH,

        /**
         * The device name exactly equals the pattern.
         *
         * Example: Pattern `"LS-PRO-2024"` matches only `"LS-PRO-2024"` (case-insensitive by default)
         */
        EQUALS,

        /**
         * The device name matches a regular expression pattern.
         *
         * Example: Pattern `"LS-.*-2024"` matches `"LS-PRO-2024"`, `"LS-LITE-2024"`, etc.
         */
        REGEX
    }
}