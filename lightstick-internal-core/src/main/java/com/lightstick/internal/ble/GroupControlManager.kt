package com.lightstick.internal.ble

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sends raw Group protocol frames (Glowsync group mapping spec v2.2) to FF02 and drives
 * the "wave" (파도타기) sequencing used by group control.
 *
 * Unlike [LedControlManager], writes here bypass the mode-byte rewrite and timeline
 * machinery: the group protocol owns all 20 bytes of the frame (see `GroupPayload` in
 * the public module), including what [LedControlManager] treats as the mode field.
 */
internal class GroupControlManager(
    private val gattClient: GattClient
) : AutoCloseable {

    companion object {
        private const val TAG = "GroupControlManager"
    }

    // Scheduling must happen on the main thread — CmdQueueManager.enqueue is documented
    // as main-thread-only, same constraint LedControlManager's monitor loop follows.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var waveJob: Job? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendPayload(bytes20: ByteArray): Boolean {
        require(bytes20.size == 20) { "Group payload must be 20 bytes" }
        return gattClient.writeCharacteristic(
            serviceUuid = UuidConstants.LCS_SERVICE,
            charUuid = UuidConstants.LCS_PAYLOAD,
            data = bytes20,
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            replaceIfSameKey = false,
            coalesceKey = null
        )
    }

    /**
     * Sends [payloads] in order, one per [intervalMs], then — if [repeat] — sends
     * [resetPayload] and starts over from the first entry. Runs until [stopWave] is
     * called or a new wave is started (which cancels this one first).
     *
     * @param onGroupSent Invoked with the 1-based index into [payloads] right after each
     *        send.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startWave(
        payloads: List<ByteArray>,
        intervalMs: Long,
        repeat: Boolean,
        resetPayload: ByteArray,
        onGroupSent: ((Int) -> Unit)?
    ) {
        require(payloads.isNotEmpty()) { "payloads must not be empty" }
        require(intervalMs > 0) { "intervalMs must be > 0" }

        waveJob?.cancel()
        waveJob = scope.launch {
            do {
                for ((index, payload) in payloads.withIndex()) {
                    if (!isActive) return@launch
                    if (!sendPayload(payload)) {
                        Log.w(TAG, "Wave send failed at index=$index — stopping")
                        return@launch
                    }
                    onGroupSent?.invoke(index + 1)
                    delay(intervalMs)
                }
                if (repeat) {
                    if (!isActive) return@launch
                    sendPayload(resetPayload)
                    delay(intervalMs)
                }
            } while (repeat && isActive)
        }
    }

    fun stopWave() {
        waveJob?.cancel()
        waveJob = null
    }

    fun isWaveRunning(): Boolean = waveJob?.isActive == true

    override fun close() {
        stopWave()
        scope.cancel()
    }
}
