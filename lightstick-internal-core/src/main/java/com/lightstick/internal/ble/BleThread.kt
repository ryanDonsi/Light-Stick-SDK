package com.lightstick.internal.ble

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * The SDK's single BLE worker thread.
 *
 * GATT command queues are driven from here instead of the app's main thread, so UI work
 * (composition, FFT, screen transitions) cannot delay effect frames, and all queue state is only
 * ever touched by this one thread — callers on the main thread, IO coroutines and GATT binder
 * callbacks hand their work over with [execute].
 */
internal object BleThread {

    private val thread: HandlerThread by lazy {
        HandlerThread("lightstick-ble", android.os.Process.THREAD_PRIORITY_DEFAULT).apply { start() }
    }

    val looper: Looper get() = thread.looper

    val handler: Handler by lazy { Handler(looper) }

    /** True when called on the BLE thread. */
    fun isCurrent(): Boolean = Looper.myLooper() == looper

    /** Runs [r] now if already on the BLE thread, otherwise posts it (FIFO with other posts). */
    fun execute(r: Runnable) {
        if (isCurrent()) r.run() else handler.post(r)
    }
}
