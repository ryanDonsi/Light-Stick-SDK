package com.lightstick.internal.ble

import com.lightstick.internal.ble.queue.CommandInterval
import org.junit.Assert.*
import org.junit.Test

class CommandIntervalTest {
    @Test fun defaultIsFiftyMs() = assertEquals(50L, CommandInterval.DEFAULT_MS)

    @Test fun acceptsBoundsAndReturnsValue() {
        assertEquals(0L, CommandInterval.validate(0))
        assertEquals(50L, CommandInterval.validate(50))
        assertEquals(1000L, CommandInterval.validate(1000))
    }

    @Test fun rejectsOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) { CommandInterval.validate(-1) }
        assertThrows(IllegalArgumentException::class.java) { CommandInterval.validate(1001) }
    }
}
