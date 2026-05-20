package com.lightstick.internal.event.monitors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.lightstick.internal.event.EventRouter
import com.lightstick.internal.util.Log

/**
 * Manifest-declared SMS BroadcastReceiver.
 *
 * - Concatenates multipart SMS parts into a single message.
 * - Forwards both body and sender (if available) to [EventRouter].
 * - Compatible with devices that return null for displayMessageBody/messageBody.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("$TAG onReceive: action=${intent.action}")

        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) {
            Log.d("$TAG onReceive: ignored (unexpected action)")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: run {
            Log.w("$TAG getMessagesFromIntent returned null, ignoring")
            return
        }
        Log.d("$TAG parsed ${messages.size} message part(s)")

        val body = buildString {
            messages.forEach { msg ->
                val part = msg.displayMessageBody ?: msg.messageBody
                if (!part.isNullOrEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(part)
                }
            }
        }.trim()

        // sender number (may be null on some devices)
        val from = messages.firstOrNull()?.originatingAddress?.takeIf { !it.isNullOrBlank() }

        Log.d("$TAG body.length=${body.length}, hasFrom=${from != null}")

        if (body.isNotEmpty()) {
            Log.i("$TAG → forwarding to EventRouter (from=${if (from != null) "present" else "null"}, bodyLen=${body.length})")
            // EventRouter has an overload that accepts both body and from
            EventRouter.onSmsReceived(body, from)
        } else {
            Log.w("$TAG body is empty after parsing, dropping event")
        }
    }
}
