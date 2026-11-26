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
 * resend is safe (the SDK advances effectIndex per call). All relays and wands must run
 * firmware >= 2.200.
 */
object ControlSamples {

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sampleShowFlow(device: Device) {
        if (!device.supportsControl()) return           // FF0A missing

        device.showEnter()                              // enter show mode (LED unchanged)
        // ... send effects with device.sendEffect(...) during the show ...
        device.pauseShow()                              // freeze every wand on its current color
        device.resumeShow()                             // restart the last effect from the beginning
        device.sendControl(ControlCmd.PAUSE, Group.GRP1 or Group.GRP2) // only groups 1 + 2
        device.showExit()                               // leave show mode
    }

    /** SYSTEM_OFF cannot be undone remotely: the app must show its own confirmation UI first. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun samplePowerOff(device: Device, userConfirmed: Boolean) {
        if (userConfirmed) device.systemOff()
    }
}
