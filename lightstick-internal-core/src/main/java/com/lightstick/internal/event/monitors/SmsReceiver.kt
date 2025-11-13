package com.lightstick.internal.event.monitors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.lightstick.internal.event.EventRouter

/**
 * Manifest-declared SMS BroadcastReceiver.
 *
 * - Concatenates multipart SMS parts into a single message.
 * - Forwards both body and sender (if available) to [EventRouter].
 * - Compatible with devices that return null for displayMessageBody/messageBody.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

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

        if (body.isNotEmpty()) {
            // EventRouter has an overload that accepts both body and from
            EventRouter.onSmsReceived(body, from)
        }
    }
}
