package com.lightstick

import android.os.Handler
import android.os.Looper

/**
 * Delivers the SDK's public callbacks on the app's main thread.
 *
 * GATT results arrive on the SDK's BLE thread, binder threads or IO coroutines. Every public
 * callback (`onResult`, `onConnected`, `onFailed`, `onProgress`, `onFound`, ...) is routed through
 * here so apps can touch UI directly from it. A callback already on the main thread runs at once;
 * otherwise it is posted, keeping the order in which the SDK raised them.
 */
internal object MainThreadCallbacks {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun run(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    fun wrap(cb: () -> Unit): () -> Unit = { run(cb) }

    fun <T> wrap(cb: (T) -> Unit): (T) -> Unit = { v -> run { cb(v) } }
}
