package com.lightstick.internal.event

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stores internal rules for ALL_CONNECTED scope.
 */
internal object GlobalEventRegistry {
    private val rules = CopyOnWriteArrayList<InternalRule>()

    fun set(all: List<InternalRule>) {
        rules.clear()
        rules.addAll(defensiveCopy(all))
    }

    fun clear() = rules.clear()

    fun get(): List<InternalRule> = rules.toList()
}

/**
 * Stores internal rules for THIS_DEVICE scope, keyed by MAC.
 */
internal object DeviceEventRegistry {
    private val byMac = ConcurrentHashMap<String, CopyOnWriteArrayList<InternalRule>>()

    fun set(mac: String, list: List<InternalRule>) {
        byMac[mac] = CopyOnWriteArrayList(defensiveCopy(list))
    }

    fun clear(mac: String) {
        byMac.remove(mac)
    }

    fun get(mac: String): List<InternalRule> = byMac[mac]?.toList().orEmpty()

    fun getAll(): Map<String, List<InternalRule>> =
        byMac.mapValues { (_, v) -> v.toList() }
}

/** Deep defensive copy for InternalRule (ByteArray payloads). */
private fun defensiveCopy(list: List<InternalRule>): List<InternalRule> =
    list.map { r ->
        val safeAction = when (val a = r.action) {
            is InternalAction.SendColorPacket -> InternalAction.SendColorPacket(a.bytes4.copyOf())
            is InternalAction.SendEffectFrame -> InternalAction.SendEffectFrame(a.bytes20.copyOf())
            is InternalAction.PlayFrames -> InternalAction.PlayFrames(
                a.entries.map { (ts, data) -> ts to data.copyOf() }
            )
        }
        r.copy(action = safeAction)
    }
