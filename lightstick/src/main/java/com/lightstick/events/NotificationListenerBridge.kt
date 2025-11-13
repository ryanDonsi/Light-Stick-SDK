package com.lightstick.events

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lightstick.internal.api.Facade

/**
 * Base class for forwarding Android notification events into the LightStick SDK event pipeline.
 *
 * Apps can subclass this and register it in AndroidManifest to let the SDK
 * react to system-level notifications (e.g., SMS, calls, or app alerts).
 *
 * Example manifest declaration:
 * ```xml
 * <service
 *   android:name=".MyNotificationListener"
 *   android:label="LightStick Notifications"
 *   android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
 *   <intent-filter>
 *     <action android:name="android.service.notification.NotificationListenerService" />
 *   </intent-filter>
 * </service>
 * ```
 *
 * The base implementation automatically forwards the standard
 * [NotificationListenerService] callbacks to the internal [Facade],
 * which dispatches them to the event router.
 *
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.EventSamples.sampleNotificationListener
 */
abstract class NotificationListenerBridge : NotificationListenerService() {

    /**
     * Called when the listener is successfully connected to the system.
     *
     * The default implementation forwards this event to [Facade.eventOnNotificationListenerConnected].
     *
     * @throws Exception Reflection errors are not used; errors are handled internally.
     * @sample com.lightstick.samples.EventSamples.sampleNotificationListener
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            Facade.eventOnNotificationListenerConnected()
        } catch (e: Exception) {
            android.util.Log.w("NotificationBridge", "onListenerConnected failed: ${e.message}", e)
        }
    }

    /**
     * Called when the listener is disconnected from the system.
     *
     * The default implementation forwards this event to [Facade.eventOnNotificationListenerDisconnected].
     *
     * @throws Exception Any errors during dispatch are caught internally.
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            Facade.eventOnNotificationListenerDisconnected()
        } catch (e: Exception) {
            android.util.Log.w("NotificationBridge", "onListenerDisconnected failed: ${e.message}", e)
        }
    }

    /**
     * Called when a new notification is posted.
     *
     * The base implementation forwards this event to [Facade.eventOnNotificationPosted].
     *
     * @param sbn The [StatusBarNotification] representing the posted notification.
     * @throws Exception Any exceptions in event forwarding are caught and logged.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        try {
            Facade.eventOnNotificationPosted(sbn)
        } catch (e: Exception) {
            android.util.Log.w("NotificationBridge", "onNotificationPosted failed: ${e.message}", e)
        }
    }

    /**
     * Called when a previously posted notification is removed.
     *
     * The base implementation forwards this event to [Facade.eventOnNotificationRemoved].
     *
     * @param sbn The [StatusBarNotification] representing the removed notification.
     * @throws Exception Any exceptions in event forwarding are caught and logged.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        try {
            Facade.eventOnNotificationRemoved(sbn)
        } catch (e: Exception) {
            android.util.Log.w("NotificationBridge", "onNotificationRemoved failed: ${e.message}", e)
        }
    }
}
