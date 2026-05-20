package com.lightstick.internal.event.monitors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import com.lightstick.internal.event.EventRouter
import com.lightstick.internal.util.Log

/**
 * Manifest-declared phone state BroadcastReceiver (legacy path).
 * - API 33+: 번호는 정책상 거의 항상 null
 * - API 32-: EXTRA_INCOMING_NUMBER 사용 (해당 라인만 @Suppress("DEPRECATION"))
 */
class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("$TAG onReceive: action=${intent.action}")

        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED != intent.action) {
            Log.d("$TAG onReceive: ignored (unexpected action)")
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: run {
            Log.w("$TAG onReceive: EXTRA_STATE is null, ignoring")
            return
        }

        // Android 13+ 에서는 번호를 읽지 않음
        val number: String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                null
            } else {
                @Suppress("DEPRECATION")
                (intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    ?: intent.getStringExtra("incoming_number"))
                    ?.takeIf { it.isNotBlank() }
            }

        val hasNumber = number != null
        Log.d("$TAG state=$state, hasNumber=$hasNumber (API ${Build.VERSION.SDK_INT})")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                Log.i("$TAG → RINGING (number=${if (hasNumber) "present" else "null"})")
                EventRouter.onCallRinging(number)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Log.i("$TAG → OFFHOOK/ACTIVE")
                EventRouter.onCallActive(number)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.i("$TAG → IDLE/ENDED")
                EventRouter.onCallIdle(number)
            }
            else -> Log.w("$TAG onReceive: unknown state='$state', ignoring")
        }
    }
}
