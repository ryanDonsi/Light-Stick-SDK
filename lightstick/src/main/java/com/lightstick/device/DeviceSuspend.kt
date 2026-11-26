package com.lightstick.device

import android.Manifest
import androidx.annotation.RequiresPermission
import com.lightstick.internal.api.Facade
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * Coroutine (suspend) versions of the callback-based [Device] API.
 *
 * Each function wraps the callback method of the same name: it suspends until the callback
 * fires, returns the value on success and throws the failure's exception otherwise. If the
 * request could not even be submitted (the callback method returned `false`, e.g. not connected),
 * it throws [IllegalStateException]. Callbacks arrive on the main thread, so these resume there
 * unless the calling coroutine's dispatcher says otherwise.
 *
 * Cancelling the coroutine cancels the wait; [connectAwait] also disconnects and [startOtaAwait]
 * also aborts the OTA, since those leave work running on the device.
 */

/**
 * Connects and suspends until the connection is ready (services discovered, device information
 * read). Returns the device information read during connection, or `null` if none is cached.
 *
 * Cancelling the coroutine disconnects (and so cancels a connection still in progress).
 *
 * @throws Throwable The connection failure passed to `onFailed`.
 * @since 2.200.0
 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun Device.connectAwait(): DeviceInfo? = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { disconnect() } }
    connect(
        onConnected = {
            val info = Facade.getInternalDeviceInfo(mac)?.let { TypeMappers.toPublic(it) }
            cont.resumeIfActive(info)
        },
        onFailed = { t -> cont.resumeWithExceptionIfActive(t) }
    )
}

/**
 * Requests an MTU and suspends until it is negotiated. Returns the negotiated MTU.
 * @since 2.200.0
 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun Device.requestMtuAwait(preferred: Int): Int =
    awaitResult("requestMtu") { cb -> requestMtu(preferred, cb) }

/** Suspend version of [Device.readDeviceName]. @since 2.200.0 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun Device.readDeviceNameAwait(): String = awaitResult("readDeviceName") { readDeviceName(it) }

/** Suspend version of [Device.readModelNumber]. @since 2.200.0 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun Device.readModelNumberAwait(): String = awaitResult("readModelNumber") { readModelNumber(it) }

/** Suspend version of [Device.readFirmwareRevision]. @since 2.200.0 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun Device.readFirmwareRevisionAwait(): String =
    awaitResult("readFirmwareRevision") { readFirmwareRevision(it) }

/** Suspend version of [Device.readManufacturer]. @since 2.200.0 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun Device.readManufacturerAwait(): String = awaitResult("readManufacturer") { readManufacturer(it) }

/** Suspend version of [Device.readMacAddress]. @since 2.200.0 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun Device.readMacAddressAwait(): String = awaitResult("readMacAddress") { readMacAddress(it) }

/** Suspend version of [Device.readBattery] (0..100). @since 2.200.0 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun Device.readBatteryAwait(): Int = awaitResult("readBattery") { readBattery(it) }

/** Suspend version of [Device.readDeviceMode]. @since 2.200.0 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun Device.readDeviceModeAwait(): DeviceMode = awaitResult("readDeviceMode") { readDeviceMode(it) }

/** Suspend version of [Device.readBroadcasting]. @since 2.200.0 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun Device.readBroadcastingAwait(): Boolean = awaitResult("readBroadcasting") { readBroadcasting(it) }

/**
 * Suspend version of [Device.fetchDeviceInfo]: reads the DIS fields and returns them together.
 * @since 2.200.0
 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun Device.fetchDeviceInfoAwait(): DeviceInfo = suspendCancellableCoroutine { cont ->
    if (!fetchDeviceInfo { info -> cont.resumeIfActive(info) }) {
        cont.resumeWithExceptionIfActive(IllegalStateException("fetchDeviceInfo not submitted: Device($mac) not connected"))
    }
}

/**
 * Bonds (pairs) and suspends until the system reports the device bonded.
 * @since 2.200.0
 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun Device.bondAwait(): Unit = suspendCancellableCoroutine { cont ->
    val submitted = bond(
        onDone = { cont.resumeIfActive(Unit) },
        onFailed = { t -> cont.resumeWithExceptionIfActive(t) }
    )
    if (!submitted) cont.resumeWithExceptionIfActive(IllegalStateException("bond not submitted: Device($mac)"))
}

/**
 * Runs OTA with [firmware] and suspends until it finishes. [onProgress] (0..100) is called on the
 * main thread. Cancelling the coroutine aborts the OTA.
 *
 * @throws IllegalStateException If OTA could not start or failed.
 * @since 2.200.0
 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun Device.startOtaAwait(
    firmware: ByteArray,
    onProgress: ((Int) -> Unit)? = null
): Unit = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { abortOta() } }
    val submitted = startOta(firmware, onProgress) { r ->
        r.fold({ cont.resumeIfActive(Unit) }, { t -> cont.resumeWithExceptionIfActive(t) })
    }
    if (!submitted) cont.resumeWithExceptionIfActive(IllegalStateException("startOta not submitted: Device($mac) not connected"))
}

// --- helpers ---

/** Calls a callback method taking `(Result<T>) -> Unit` and suspends for its result. */
private suspend inline fun <T> Device.awaitResult(
    name: String,
    crossinline submit: (cb: (Result<T>) -> Unit) -> Boolean
): T = suspendCancellableCoroutine { cont ->
    val submitted = submit { r -> r.fold({ cont.resumeIfActive(it) }, { t -> cont.resumeWithExceptionIfActive(t) }) }
    if (!submitted) cont.resumeWithExceptionIfActive(IllegalStateException("$name not submitted: Device($mac) not connected"))
}

private fun <T> CancellableContinuation<T>.resumeIfActive(value: T) {
    if (isActive) resume(value)
}

private fun <T> CancellableContinuation<T>.resumeWithExceptionIfActive(t: Throwable) {
    if (isActive) resumeWithException(t)
}
