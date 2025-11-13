package com.lightstick.internal.event.monitors

import android.service.notification.StatusBarNotification
import com.lightstick.internal.event.EventRouter

object NotificationMonitor {
    fun forwardPosted(sbn: StatusBarNotification) = EventRouter.onNotificationPosted(sbn)
    fun forwardRemoved(sbn: StatusBarNotification) = EventRouter.onNotificationRemoved(sbn)
}
