package com.lightstick.internal.event

import android.annotation.SuppressLint
import com.lightstick.internal.api.Facade
import com.lightstick.internal.util.Log as LSLog

internal object EventBridge {

    private const val TAG = "EventBridge"

    fun onEvent(e: InternalEvent) {
        LSLog.d("$TAG onEvent: type=${e.type}")
        try {
            val globalRules = GlobalEventRegistry.get()
            LSLog.d("$TAG checking ${globalRules.size} global rule(s) for ${e.type}")
            var globalMatches = 0
            for (rule in globalRules) {
                if (rule.trigger.type == e.type) {
                    globalMatches++
                    LSLog.i("$TAG global rule matched (id=${rule.id}, target=${rule.target}, action=${rule.action::class.simpleName})")
                    execute(rule.target, rule.action)
                    if (rule.stopAfterMatch) {
                        LSLog.d("$TAG stopAfterMatch=true, stopping global rule scan")
                        break
                    }
                }
            }
            if (globalMatches == 0) LSLog.d("$TAG no global rules matched for ${e.type}")

            val deviceRules = DeviceEventRegistry.getAll()
            LSLog.d("$TAG checking device rules for ${deviceRules.size} device(s)")
            for ((mac, rules) in deviceRules) {
                var deviceMatches = 0
                for (rule in rules) {
                    if (rule.trigger.type == e.type) {
                        deviceMatches++
                        LSLog.i("$TAG device rule matched (mac=${mac.takeLast(5)}, id=${rule.id}, action=${rule.action::class.simpleName})")
                        execute(InternalTarget.Address(mac), rule.action)
                        if (rule.stopAfterMatch) {
                            LSLog.d("$TAG stopAfterMatch=true, stopping rule scan for mac=${mac.takeLast(5)}")
                            break
                        }
                    }
                }
                if (deviceMatches == 0) LSLog.d("$TAG no rules matched for device mac=${mac.takeLast(5)}")
            }
        } catch (t: Throwable) {
            LSLog.e("$TAG onEvent() failed: ${t.message}", t)
        }
    }

    private inline fun runSafely(block: () -> Unit) {
        try { block() }
        catch (se: SecurityException) {
            LSLog.w("$TAG BLUETOOTH_CONNECT missing/revoked; skipping action.")
        } catch (t: Throwable) {
            LSLog.w("$TAG BLE action failed: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun execute(target: InternalTarget, action: InternalAction) {
        LSLog.d("$TAG execute: target=${target::class.simpleName}, action=${action::class.simpleName}")
        when (target) {
            is InternalTarget.All -> when (action) {
                is InternalAction.SendColorPacket -> runSafely { Facade.sendColorPacket(action.bytes4) }
                is InternalAction.SendEffectFrame -> runSafely { Facade.sendEffectPayload(action.bytes20) }
                is InternalAction.PlayFrames      -> runSafely { Facade.playAllEntries(action.entries) }
            }
            is InternalTarget.Address -> when (action) {
                is InternalAction.SendColorPacket -> runSafely { Facade.sendColorTo(target.mac, action.bytes4) }
                is InternalAction.SendEffectFrame -> runSafely { Facade.sendEffectTo(target.mac, action.bytes20) }
                is InternalAction.PlayFrames      -> runSafely { Facade.playEntries(target.mac, action.entries) }
            }
        }
    }
}
