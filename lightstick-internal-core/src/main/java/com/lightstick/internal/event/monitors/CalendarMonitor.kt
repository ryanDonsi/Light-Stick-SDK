package com.lightstick.internal.event.monitors

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.lightstick.internal.event.EventRouter

object CalendarMonitor {

    private var observer: ContentObserver? = null
    private val uri: Uri = CalendarContract.Instances.CONTENT_URI

    fun register(context: Context) {
        if (observer != null) return
        val h = Handler(Looper.getMainLooper())
        observer = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean) {
                // TODO refine: query instances to determine start/end
                EventRouter.onCalendarStart(title = null, location = null)
            }
        }
        context.contentResolver.registerContentObserver(uri, true, observer!!)
    }

    fun unregister(context: Context) {
        observer?.let {
            context.contentResolver.unregisterContentObserver(it)
            observer = null
        }
    }
}
