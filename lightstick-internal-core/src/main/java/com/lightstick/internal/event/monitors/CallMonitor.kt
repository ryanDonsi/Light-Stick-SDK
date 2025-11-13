package com.lightstick.internal.event.monitors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.lightstick.internal.event.EventRouter

/**
 * Phone state monitor using TelephonyCallback (API 31+).
 *
 * - API 31(S)+에서만 동작.
 * - 필수 권한: READ_PHONE_STATE (없으면 등록하지 않음)
 * - TelephonyCallback은 번호를 제공하지 않으므로 항상 number=null 전달.
 * - 하위(API 30-)는 Manifest BroadcastReceiver(CallReceiver)가 처리.
 */
internal object CallMonitor {

    private var telephonyManager: TelephonyManager? = null
    private var callback: TelephonyCallback? = null

    fun register(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (callback != null) return

        val hasReadPhoneState =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                    PackageManager.PERMISSION_GRANTED
        if (!hasReadPhoneState) return

        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        val executor = ContextCompat.getMainExecutor(context)

        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                // TelephonyCallback은 번호를 제공하지 않음 → 항상 null
                val number: String? = null
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> EventRouter.onCallRinging(number)
                    TelephonyManager.CALL_STATE_OFFHOOK -> EventRouter.onCallActive(number)
                    TelephonyManager.CALL_STATE_IDLE    -> EventRouter.onCallIdle(number)
                }
            }
        }

        try {
            tm.registerTelephonyCallback(executor, cb)
            telephonyManager = tm
            callback = cb
        } catch (_: SecurityException) {
            // 권한 거부 등 → 무시
        } catch (_: Throwable) {
            // 제조사 변형 등 예외 → 무시
        }
    }

    fun unregister(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        telephonyManager?.let { tm ->
            callback?.let { cb ->
                runCatching { tm.unregisterTelephonyCallback(cb) }
            }
        }
        telephonyManager = null
        callback = null
    }
}
