package com.lightstick.internal.event

import android.content.Context
import com.lightstick.internal.util.Log
import java.lang.ref.WeakReference

object EventRouter {

    private const val TAG = "EventRouter"

    private var appCtxRef: WeakReference<Context>? = null
    internal fun appContextOrNull(): Context? = appCtxRef?.get()

    @JvmStatic
    fun initialize(context: Context) {
        appCtxRef = WeakReference(context.applicationContext)
        Log.i("$TAG initialized")
    }

    @JvmStatic
    fun enable() {
        Log.i("$TAG enable()")
    }

    @JvmStatic
    fun disable() {
        Log.i("$TAG disable()")
    }

    // ---- Hooks ---------------------------------------------------------------

    @JvmStatic
    fun onCustomEvent() {
        Log.d("$TAG onCustomEvent")
        EventBridge.onEvent(InternalEvent(type = EventType.CUSTOM))
    }

    @JvmStatic fun onNotificationListenerConnected() { /* no-op */ }
    @JvmStatic fun onNotificationListenerDisconnected() { /* no-op */ }

    @JvmStatic
    fun onNotificationPosted(@Suppress("UNUSED_PARAMETER") sbn: android.service.notification.StatusBarNotification) {
        EventBridge.onEvent(InternalEvent(type = EventType.CUSTOM))
    }

    @JvmStatic
    fun onNotificationRemoved(@Suppress("UNUSED_PARAMETER") sbn: android.service.notification.StatusBarNotification) {
        // no-op
    }
}
