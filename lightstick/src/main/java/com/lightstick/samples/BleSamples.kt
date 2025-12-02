@file:Suppress(
    "UNUSED_PARAMETER",    // 사용하지 않는 인자
    "UNUSED_VARIABLE",     // 사용하지 않는 지역변수
    "UNUSED_EXPRESSION",   // println 등 결과 미사용
    "MissingPermission",   // Android BLE 예시용 코드
    "RedundantVisibilityModifier",
    "MemberVisibilityCanBePrivate",
    "CanBeVal"
)

package com.lightstick.samples

import android.content.Context
import android.util.Log
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.types.Color
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload

/**
 * BLE-related usage samples for public SDK facade.
 */
object BleSamples {

    /** Initialize SDK once (e.g., in Application.onCreate). */
    fun sampleInitialize(context: Context) {
        LSBluetooth.initialize(context)
    }

    /**
     * Scan for devices, pick the first matching target, stop scan, then connect.
     *
     * - 실제 앱에서는 이름/ManufacturerData/RSSI 등 필터를 강화하세요.
     * - 연결에 성공하면 간단한 테스트 패킷(RGB)을 전송합니다.
     */
    fun sampleScanAndConnect(context: Context) {
        LSBluetooth.initialize(context)

        var connectedOrConnecting = false

        LSBluetooth.startScan { dev ->
            // 간단한 이름 기반 필터(예: "LightStick"로 시작)
            val match = dev.name?.startsWith("LightStick", ignoreCase = true) == true

            if (!connectedOrConnecting && match) {
                connectedOrConnecting = true
                // 스캔 중단 후 연결 시도
                LSBluetooth.stopScan()

                Log.d("BleSamples", "Connecting to ${dev.mac} (${dev.name})")

                dev.connect(
                    onConnected = { ctl ->
                        Log.d("BleSamples", "Connected: ${ctl.device.mac}")
                        // 연결 테스트: 파랑색 전송
                        ctl.sendColor(Colors.BLUE, transition = 8)
                        // 간단한 이펙트도 한 번
                        ctl.sendEffect(LSEffectPayload.Effects.blink(6, Colors.CYAN))
                    },
                    onFailed = { t ->
                        Log.w("BleSamples", "Connect failed: ${t.message}", t)
                    }
                )
            }
        }
    }

    /** Start scanning and print discovered devices. */
    fun sampleStartScan(context: Context) {
        LSBluetooth.initialize(context)
        LSBluetooth.startScan { dev ->
            println("Found: ${dev.mac} name=${dev.name} rssi=${dev.rssi}")
        }
    }

    /** Stop scanning. */
    fun sampleStopScan() {
        LSBluetooth.stopScan()
    }

    /** Enumerate connected devices. */
    fun sampleConnectedDevices(): List<Device> {
        return LSBluetooth.connectedDevices().also {
            println("Connected: ${it.size}")
            it.forEach { d -> println(" - ${d.mac} (${d.name})") }
        }
    }

    /** Read connected count. */
    fun sampleConnectedCount(): Int {
        val n = LSBluetooth.connectedCount()
        println("Connected count: $n")
        return n
    }

    /** Enumerate bonded devices. */
    fun sampleBondedDevices(): List<Device> {
        return LSBluetooth.bondedDevices().also {
            println("Bonded: ${it.size}")
            it.forEach { d -> println(" - ${d.mac} (${d.name})") }
        }
    }

    /** Read bonded count. */
    fun sampleBondedCount(): Int {
        val n = LSBluetooth.bondedCount()
        println("Bonded count: $n")
        return n
    }

    /** Broadcast a color to all connected devices. */
    fun sampleBroadcastColor() {
        LSBluetooth.broadcastColor(Color(255, 80, 0), transition = 16)
    }

    /** Broadcast a single-frame effect to all connected devices. */
    fun sampleBroadcastEffect() {
        // You can construct payload via LSEffectPayload.Effects.* helpers
        val payload = LSEffectPayload.Effects.blink(
            color = Colors.BLUE, period = 10
        )
        LSBluetooth.broadcastEffect(payload)
    }

    /** Shutdown all sessions and release resources. */
    fun sampleShutdown() {
        LSBluetooth.shutdown()
    }
}
