package com.lightstick.internal.event.monitors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import com.lightstick.internal.event.EventRouter

/**
 * Manifest-declared phone state BroadcastReceiver (legacy path).
 * - API 33+: 번호는 정책상 거의 항상 null
 * - API 32-: EXTRA_INCOMING_NUMBER 사용 (해당 라인만 @Suppress("DEPRECATION"))
 */
class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED != intent.action) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

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

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> EventRouter.onCallRinging(number)
            TelephonyManager.EXTRA_STATE_OFFHOOK -> EventRouter.onCallActive(number)
            TelephonyManager.EXTRA_STATE_IDLE    -> EventRouter.onCallIdle(number) // ✅ 종료 처리
        }
    }
}
