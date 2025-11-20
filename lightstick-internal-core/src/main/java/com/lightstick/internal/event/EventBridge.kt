package com.lightstick.internal.event

import android.annotation.SuppressLint
import android.util.Log
import com.lightstick.internal.api.Facade

internal object EventBridge {

    // 내부 전용: OFF(검정, 즉시 전환)
    private val OFF4 = byteArrayOf(0, 0, 0, 0)

    fun onEvent(e: InternalEvent) {
        try {
            // 1) 내부 전용 가로채기: CALL_ACTIVE / CALL_IDLE → 즉시 OFF, 룰 매칭 생략
            if (e.type == EventType.CALL_ACTIVE || e.type == EventType.CALL_IDLE) {
                sendOffToAll()
                return
            }

            // 2) 일반 이벤트는 기존처럼 전역/디바이스 룰 매칭
            for (rule in GlobalEventRegistry.get()) {
                if (rule.trigger.type == e.type && filterMatch(rule.trigger.filter, e.payload)) {
                    execute(rule.target, rule.action); if (rule.stopAfterMatch) break
                }
            }
            for ((mac, rules) in DeviceEventRegistry.getAll()) {
                for (rule in rules) {
                    if (rule.trigger.type == e.type && filterMatch(rule.trigger.filter, e.payload)) {
                        execute(InternalTarget.Address(mac), rule.action); if (rule.stopAfterMatch) break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("EventBridge", "onEvent() failed: ${t.message}", t)
        }
    }

    /** OFF 패킷을 모든 연결 대상에 전송.
     *  Context 접근이 불가하여 권한 체크 대신 SecurityException 가드 + Lint 억제 사용.
     */
    @SuppressLint("MissingPermission")
    private fun sendOffToAll() {
        runSafely { Facade.sendColorPacket(OFF4) }
    }

    private fun filterMatch(f: InternalFilter, p: InternalPayload): Boolean {
        if (f.smsContains != null && (p.smsBody?.contains(f.smsContains) != true)) return false
        if (f.phoneNumber != null && ((p.phoneNumber ?: "") != f.phoneNumber)) return false
        if (f.calendarTitle != null && (p.calendarTitle?.contains(f.calendarTitle) != true)) return false
        if (f.calendarLocation != null && (p.calendarLocation?.contains(f.calendarLocation) != true)) return false
        return true
    }

    private inline fun runSafely(block: () -> Unit) {
        try { block() }
        catch (se: SecurityException) {
            Log.w("EventBridge", "BLUETOOTH_CONNECT missing/revoked; skipping action.", se)
        } catch (t: Throwable) {
            Log.w("EventBridge", "BLE action failed: ${t.message}", t)
        }
    }

    @SuppressLint("MissingPermission")
    private fun execute(target: InternalTarget, action: InternalAction) {
        when (target) {
            is InternalTarget.All -> when (action) {
                is InternalAction.SendColorPacket -> runSafely { Facade.sendColorPacket(action.bytes4) }
                is InternalAction.SendEffectFrame -> runSafely { Facade.sendEffectPayload(action.bytes20) }
                is InternalAction.PlayFrames     -> runSafely { Facade.playFrames(action.entries) }
            }
            is InternalTarget.Address -> when (action) {
                is InternalAction.SendColorPacket -> runSafely { Facade.sendColorTo(target.mac, action.bytes4) }
                is InternalAction.SendEffectFrame -> runSafely { Facade.sendEffectTo(target.mac, action.bytes20) }
                is InternalAction.PlayFrames     -> runSafely { Facade.playEntries(target.mac, action.entries) }
            }
        }
    }
}
