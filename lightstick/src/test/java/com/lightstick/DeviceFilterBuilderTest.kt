package com.lightstick.test

import com.lightstick.config.DeviceFilter
import org.junit.Assert.*
import org.junit.Test

/**
 * [DeviceFilter.Builder] unit tests — no BLE connection needed.
 */
class DeviceFilterBuilderTest {

    @Test
    fun testSingleAddNameMatchesPlainByName() {
        val built = DeviceFilter.Builder()
            .addName("RL", DeviceFilter.MatchMode.ENDS_WITH)
            .build()
        val direct = DeviceFilter.byName("RL", DeviceFilter.MatchMode.ENDS_WITH)

        assertEquals(direct, built)
    }

    @Test
    fun testMultipleAddNameOrCombinesInOrder() {
        val built = DeviceFilter.Builder()
            .addName("RL", DeviceFilter.MatchMode.ENDS_WITH)
            .addName("GL", DeviceFilter.MatchMode.ENDS_WITH)
            .build()

        assertEquals(DeviceFilter.FilterType.OR, built.type)
        val parts = built.combinedFilters
        assertNotNull(parts)
        assertEquals(2, parts!!.size)
        assertEquals("RL", parts[0].pattern)
        assertEquals("GL", parts[1].pattern)
        assertTrue(parts.all { it.matchMode == DeviceFilter.MatchMode.ENDS_WITH })
    }

    @Test
    fun testAddNameDefaultsMatchByName() {
        val built = DeviceFilter.Builder().addName("LS").build()
        val direct = DeviceFilter.byName("LS")

        assertEquals(direct, built)
    }

    @Test
    fun testBuildWithoutAddNameThrows() {
        assertThrows(IllegalStateException::class.java) {
            DeviceFilter.Builder().build()
        }
    }

    @Test
    fun testBuilderReturnsSameInstanceForChaining() {
        val builder = DeviceFilter.Builder()
        val returned = builder.addName("RL")
        assertSame(builder, returned)
    }
}
