package com.lightstick.config

import com.lightstick.internal.ble.DeviceFilter as InternalDeviceFilter
import com.lightstick.device.Device

/**
 * Internal mapper for converting public DeviceFilter to internal implementation.
 *
 * This mapper is part of the type mapping layer that separates public API types
 * from internal implementation details, following the same pattern used for
 * ConnectionState, DeviceInfo, and other SDK types.
 */
internal object DeviceFilterMapper {

    /**
     * Converts public DeviceFilter to internal DeviceFilter implementation.
     *
     * @receiver The public DeviceFilter to convert.
     * @return The internal DeviceFilter implementation.
     */
    fun DeviceFilter.toInternal(): InternalDeviceFilter {
        return when (type) {
            DeviceFilter.FilterType.NAME -> {
                InternalDeviceFilter.byName(
                    pattern = pattern!!,
                    mode = matchMode!!.toInternal(),
                    ignoreCase = ignoreCase
                )
            }

            DeviceFilter.FilterType.MAC_PREFIX -> {
                InternalDeviceFilter.byMacPrefix(pattern!!)
            }

            DeviceFilter.FilterType.MAC_ADDRESS -> {
                InternalDeviceFilter.byMacAddress(pattern!!)
            }

            DeviceFilter.FilterType.MIN_RSSI -> {
                InternalDeviceFilter.byMinRssi(minRssi!!)
            }

            DeviceFilter.FilterType.RSSI_RANGE -> {
                InternalDeviceFilter.byRssiRange(minRssi!!, maxRssi!!)
            }

            DeviceFilter.FilterType.CUSTOM -> {
                // Convert Device-based predicate to primitive-based predicate
                InternalDeviceFilter.custom { mac, name, rssi ->
                    customPredicate!!.invoke(
                        Device(mac, name, rssi)
                    )
                }
            }

            DeviceFilter.FilterType.AND -> {
                val filters = combinedFilters!!.map { it.toInternal() }
                InternalDeviceFilter.allOf(*filters.toTypedArray())
            }

            DeviceFilter.FilterType.OR -> {
                val filters = combinedFilters!!.map { it.toInternal() }
                InternalDeviceFilter.anyOf(*filters.toTypedArray())
            }

            DeviceFilter.FilterType.NOT -> {
                combinedFilters!!.first().toInternal().not()
            }

            DeviceFilter.FilterType.ACCEPT_ALL -> {
                InternalDeviceFilter.acceptAll()
            }

            DeviceFilter.FilterType.REJECT_ALL -> {
                InternalDeviceFilter.rejectAll()
            }
        }
    }

    /**
     * Converts public MatchMode to internal MatchMode.
     *
     * @receiver The public MatchMode to convert.
     * @return The internal MatchMode.
     */
    private fun DeviceFilter.MatchMode.toInternal(): InternalDeviceFilter.MatchMode {
        return when (this) {
            DeviceFilter.MatchMode.CONTAINS ->
                InternalDeviceFilter.MatchMode.CONTAINS
            DeviceFilter.MatchMode.STARTS_WITH ->
                InternalDeviceFilter.MatchMode.STARTS_WITH
            DeviceFilter.MatchMode.ENDS_WITH ->
                InternalDeviceFilter.MatchMode.ENDS_WITH
            DeviceFilter.MatchMode.EQUALS ->
                InternalDeviceFilter.MatchMode.EQUALS
            DeviceFilter.MatchMode.REGEX ->
                InternalDeviceFilter.MatchMode.REGEX
        }
    }
}