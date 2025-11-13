package com.lightstick.internal.event

import android.content.Context
import android.service.notification.StatusBarNotification
import java.lang.ref.WeakReference

object EventRouter {

    private var appCtxRef: WeakReference<Context>? = null
    internal fun appContextOrNull(): Context? = appCtxRef?.get()

    @JvmStatic
    fun initialize(context: Context) {
        appCtxRef = WeakReference(context.applicationContext)
    }

    @JvmStatic
    fun enable() {
        appContextOrNull()?.let { ctx ->
            // ✅ 올바른 패키지 경로 (events.monitor)
            com.lightstick.internal.event.monitors.CalendarMonitor.register(ctx)

            // ✅ runCatching 대신 try/catch 사용 (심볼/타입 추론 문제 회피)
            try {
                com.lightstick.internal.event.monitors.CallMonitor.register(ctx)
            } catch (_: Throwable) {
                // 하위버전/모듈 미포함 등 -> 무시
            }
        }
    }

    @JvmStatic
    fun disable() {
        appContextOrNull()?.let { ctx ->
            com.lightstick.internal.event.monitors.CalendarMonitor.unregister(ctx)
            try {
                com.lightstick.internal.event.monitors.CallMonitor.unregister(ctx)
            } catch (_: Throwable) {
                // best-effort
            }
        }
    }

    // ---- Hooks ---------------------------------------------------------------

    @JvmStatic
    fun onSmsReceived(body: String) {
        EventBridge.onEvent(
            InternalEvent(
                type = EventType.SMS_RECEIVED,
                payload = InternalPayload(smsBody = body)
            )
        )
    }

    @JvmStatic
    fun onSmsReceived(body: String?, from: String?) {
        EventBridge.onEvent(
            InternalEvent(
                type = EventType.SMS_RECEIVED,
                payload = InternalPayload(smsBody = body, phoneNumber = from)
            )
        )
    }

    @JvmStatic
    fun onCallRinging(incomingNumber: String?) {
        EventBridge.onEvent(
            InternalEvent(
                type = EventType.CALL_RINGING,
                payload = InternalPayload(phoneNumber = incomingNumber)
            )
        )
    }

    @JvmStatic
    fun onCallActive(number: String?) {
        EventBridge.onEvent(
            InternalEvent(
                type = EventType.CALL_ACTIVE,
                payload = InternalPayload(phoneNumber = number)
            )
        )
    }

    @JvmStatic
    fun onCallIdle(number: String?) {
        EventBridge.onEvent(
            InternalEvent(
                type = EventType.CALL_IDLE,
                payload = InternalPayload(phoneNumber = number)
            )
        )
    }

    @JvmStatic
    fun onCalendarStart(title: String?, location: String?) {
        EventBridge.onEvent(
            InternalEvent(
                type = EventType.CALENDAR_START,
                payload = InternalPayload(calendarTitle = title, calendarLocation = location)
            )
        )
    }

    @JvmStatic
    fun onCalendarEnd(title: String?, location: String?) {
        EventBridge.onEvent(
            InternalEvent(
                type = EventType.CALENDAR_END,
                payload = InternalPayload(calendarTitle = title, calendarLocation = location)
            )
        )
    }

    @JvmStatic fun onNotificationListenerConnected() { /* no-op */ }
    @JvmStatic fun onNotificationListenerDisconnected() { /* no-op */ }

    @JvmStatic
    fun onNotificationPosted(@Suppress("UNUSED_PARAMETER") sbn: StatusBarNotification) {
        EventBridge.onEvent(InternalEvent(type = EventType.CUSTOM))
    }

    @JvmStatic
    fun onNotificationRemoved(@Suppress("UNUSED_PARAMETER") sbn: StatusBarNotification) {
        // no-op
    }
}
