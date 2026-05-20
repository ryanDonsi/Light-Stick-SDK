package com.lightstick.internal.event

import android.annotation.SuppressLint
import android.util.Log
import com.lightstick.internal.api.Facade
import com.lightstick.internal.util.Log as LSLog

internal object EventBridge {

    private const val TAG = "EventBridge"

    // 내부 전용: OFF(검정, 즉시 전환)
    private val OFF4 = byteArrayOf(0, 0, 0, 0)

    fun onEvent(e: InternalEvent) {
        LSLog.d("$TAG onEvent: type=${e.type}, payload=$e.payload")
        try {
            // 1) 내부 전용 가로채기: CALL_ACTIVE / CALL_IDLE → 즉시 OFF, 룰 매칭 생략
            if (e.type == EventType.CALL_ACTIVE || e.type == EventType.CALL_IDLE) {
                LSLog.i("$TAG ${e.type} → sendOffToAll() (bypassing rule matching)")
                sendOffToAll()
                return
            }

            // 2) 일반 이벤트는 기존처럼 전역/디바이스 룰 매칭
            val globalRules = GlobalEventRegistry.get()
            LSLog.d("$TAG checking ${globalRules.size} global rule(s) for ${e.type}")
            var globalMatches = 0
            for (rule in globalRules) {
                if (rule.trigger.type == e.type && filterMatch(rule.trigger.filter, e.payload)) {
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
                    if (rule.trigger.type == e.type && filterMatch(rule.trigger.filter, e.payload)) {
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
            Log.w(TAG, "onEvent() failed: ${t.message}", t)
        }
    }

    /** OFF 패킷을 모든 연결 대상에 전송.
     *  Context 접근이 불가하여 권한 체크 대신 SecurityException 가드 + Lint 억제 사용.
     */
    @SuppressLint("MissingPermission")
    private fun sendOffToAll() {
        LSLog.d("$TAG sendOffToAll: sending OFF(0,0,0,0) to all connected devices")
        runSafely { Facade.sendColorPacket(OFF4) }
    }

    private fun filterMatch(f: InternalFilter, p: InternalPayload): Boolean {
        if (f.smsContains != null && (p.smsBody?.contains(f.smsContains) != true)) {
            LSLog.d("$TAG filterMatch: FAIL smsContains='${f.smsContains}' not found in body")
            return false
        }
        if (f.phoneNumber != null && ((p.phoneNumber ?: "") != f.phoneNumber)) {
            LSLog.d("$TAG filterMatch: FAIL phoneNumber mismatch (filter=${f.phoneNumber}, payload=${p.phoneNumber})")
            return false
        }
        if (f.calendarTitle != null && (p.calendarTitle?.contains(f.calendarTitle) != true)) {
            LSLog.d("$TAG filterMatch: FAIL calendarTitle='${f.calendarTitle}' not found")
            return false
        }
        if (f.calendarLocation != null && (p.calendarLocation?.contains(f.calendarLocation) != true)) {
            LSLog.d("$TAG filterMatch: FAIL calendarLocation='${f.calendarLocation}' not found")
            return false
        }
        LSLog.d("$TAG filterMatch: PASS")
        return true
    }

    private inline fun runSafely(block: () -> Unit) {
        try { block() }
        catch (se: SecurityException) {
            LSLog.w("$TAG BLUETOOTH_CONNECT missing/revoked; skipping action.")
            Log.w(TAG, "BLUETOOTH_CONNECT missing/revoked; skipping action.", se)
        } catch (t: Throwable) {
            LSLog.w("$TAG BLE action failed: ${t.message}")
            Log.w(TAG, "BLE action failed: ${t.message}", t)
        }
    }

    @SuppressLint("MissingPermission")
    private fun execute(target: InternalTarget, action: InternalAction) {
        LSLog.d("$TAG execute: target=${target::class.simpleName}, action=${action::class.simpleName}")
        when (target) {
            is InternalTarget.All -> when (action) {
                is InternalAction.SendColorPacket -> runSafely { Facade.sendColorPacket(action.bytes4) }
                is InternalAction.SendEffectFrame -> runSafely { Facade.sendEffectPayload(action.bytes20) }
                is InternalAction.PlayFrames     -> runSafely { Facade.playAllEntries(action.entries) }
            }
            is InternalTarget.Address -> when (action) {
                is InternalAction.SendColorPacket -> runSafely { Facade.sendColorTo(target.mac, action.bytes4) }
                is InternalAction.SendEffectFrame -> runSafely { Facade.sendEffectTo(target.mac, action.bytes20) }
                is InternalAction.PlayFrames     -> runSafely { Facade.playEntries(target.mac, action.entries) }
            }
        }
    }
}
