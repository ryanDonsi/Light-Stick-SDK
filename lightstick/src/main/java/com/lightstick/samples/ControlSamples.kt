package com.lightstick.samples

import android.Manifest
import androidx.annotation.RequiresPermission
import com.lightstick.control.ControlCmd
import com.lightstick.device.Device
import com.lightstick.types.Group

/**
 * Show control (BLE protocol v2.2, FF0A) usage samples.
 *
 * Control frames are broadcast without ACK; commands are idempotent on the wand side, so a
 * resend is safe (the SDK advances effectIndex per call). Send them only after every relay and
 * wand runs firmware >= 2.200 — see `Device.sendControl` for the version limits.
 */
object ControlSamples {

    /** Guard with FF0A presence *and* the firmware version of the connected device. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sampleShowFlow(device: Device) {
        device.supportsProtocol(2, 2) { v22 ->
            if (!v22 || !device.supportsControl()) return@supportsProtocol

            device.showEnter()                              // enter show mode (LED unchanged)
            // ... send effects with device.sendEffect(...) during the show ...
            device.pauseShow()                              // freeze every wand on its current color
            device.resumeShow()                             // restart the last effect from the beginning
            device.sendControl(ControlCmd.PAUSE, Group.GRP1 or Group.GRP2) // only groups 1 + 2
            device.showExit()                               // leave show mode
        }
    }

    /** SYSTEM_OFF cannot be undone remotely: the app must show its own confirmation UI first. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun samplePowerOff(device: Device, userConfirmed: Boolean) {
        if (userConfirmed) device.systemOff()
    }
}
