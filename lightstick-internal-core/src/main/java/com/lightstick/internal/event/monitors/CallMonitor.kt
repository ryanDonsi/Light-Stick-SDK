package com.lightstick.internal.event.monitors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.lightstick.internal.event.EventRouter
import com.lightstick.internal.util.Log

/**
 * Phone state monitor using TelephonyCallback (API 31+).
 *
 * - API 31(S)+에서만 동작.
 * - 필수 권한: READ_PHONE_STATE (없으면 등록하지 않음)
 * - TelephonyCallback은 번호를 제공하지 않으므로 항상 number=null 전달.
 * - 하위(API 30-)는 Manifest BroadcastReceiver(CallReceiver)가 처리.
 */
internal object CallMonitor {

    private const val TAG = "CallMonitor"

    private var telephonyManager: TelephonyManager? = null
    private var callback: TelephonyCallback? = null

    fun register(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.d("$TAG register() skipped: API ${Build.VERSION.SDK_INT} < S(31), using CallReceiver instead")
            return
        }
        if (callback != null) {
            Log.d("$TAG register() skipped: already registered")
            return
        }

        val hasReadPhoneState =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                    PackageManager.PERMISSION_GRANTED
        if (!hasReadPhoneState) {
            Log.w("$TAG register() skipped: READ_PHONE_STATE permission not granted")
            return
        }

        val tm = context.getSystemService(TelephonyManager::class.java) ?: run {
            Log.w("$TAG register() skipped: TelephonyManager not available")
            return
        }
        val executor = ContextCompat.getMainExecutor(context)

        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                val stateName = when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
                    TelephonyManager.CALL_STATE_IDLE    -> "IDLE"
                    else -> "UNKNOWN($state)"
                }
                Log.d("$TAG onCallStateChanged: state=$stateName (number not available via TelephonyCallback)")
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
            Log.i("$TAG registered TelephonyCallback (API ${Build.VERSION.SDK_INT})")
        } catch (se: SecurityException) {
            Log.w("$TAG registerTelephonyCallback() failed: SecurityException - ${se.message}")
        } catch (t: Throwable) {
            Log.w("$TAG registerTelephonyCallback() failed: ${t.message}")
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
        Log.d("$TAG unregistered TelephonyCallback")
    }
}
