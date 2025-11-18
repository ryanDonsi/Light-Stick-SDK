package com.lightstick.test

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.lightstick.LSBluetooth
import com.lightstick.device.Controller
import com.lightstick.device.Device
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Light Stick 기본 BLE 테스트 (권한 처리 포함)
 *
 * 실제 디바이스에서 실행되는 간단한 통합 테스트입니다.
 *
 * 실행 방법:
 * 1. Android 디바이스를 연결합니다.
 * 2. Light Stick 디바이스의 전원을 켭니다.
 * 3. Android Studio에서 이 테스트를 실행합니다.
 */
@RunWith(AndroidJUnit4::class)
class SimpleBleTest {

    private lateinit var context: Context
    private var controller: Controller? = null
    private var testDevice: Device? = null

    companion object {
        private const val TAG = "SimpleBleTest"
    }

    /**
     * BLE 권한 자동 부여
     * Android 12 이상에서 필요한 모든 BLE 권한을 테스트 시작 전에 부여합니다.
     */
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        LSBluetooth.initialize(context)

        // 권한 확인 로그
        checkPermissions()

        Log.d(TAG, "=== Test Started ===")
    }

    @After
    fun tearDown() {
        testDevice?.disconnect()
        controller = null
        testDevice = null

        // 스캔 중지
        try {
            LSBluetooth.stopScan()
        } catch (e: Exception) {
            // Ignore
        }
        Log.d(TAG, "=== Test Completed ===")
    }

    /**
     * 권한 확인 헬퍼 메서드
     */
    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        permissions.forEach { permission ->
            val granted = ContextCompat.checkSelfPermission(
                context, permission
            ) == PackageManager.PERMISSION_GRANTED

            Log.d(TAG, "Permission $permission: ${if (granted) "✓ GRANTED" else "✗ DENIED"}")
        }
    }

    @Test
    fun testPermissionsGranted() {
        Log.d(TAG, "Testing permissions...")

        val scanGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED

        val connectGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        assertTrue("BLUETOOTH_SCAN permission should be granted", scanGranted)
        assertTrue("BLUETOOTH_CONNECT permission should be granted", connectGranted)

        Log.d(TAG, "✓ All required permissions are granted")
    }

    @Test
    fun testScanDevices() {
        Log.d(TAG, "Starting scan test...")

        val latch = CountDownLatch(1)
        var deviceFound = false

        LSBluetooth.startScan { device ->
            Log.d(TAG, "📱 Found: ${device.mac} | ${device.name} | RSSI: ${device.rssi}")
            deviceFound = true
            latch.countDown()
        }

        latch.await(10, TimeUnit.SECONDS)
        LSBluetooth.stopScan()

        assertTrue("스캔에서 디바이스를 찾지 못했습니다", deviceFound)
        Log.d(TAG, "✓ Scan test passed")
    }

    @Test
    fun testConnectAndSendColor() {
        Log.d(TAG, "Starting connect and color test...")

        val scanLatch = CountDownLatch(1)
        val connectLatch = CountDownLatch(1)
        var targetDevice: Device? = null
        var connected = false

        // 1. 스캔
        LSBluetooth.startScan { device ->
            if (device.name?.endsWith("LS") == true) {
                Log.d(TAG, "🎯 Target found: ${device.name}")
                targetDevice = device
                scanLatch.countDown()
            }
        }

        scanLatch.await(10, TimeUnit.SECONDS)
        LSBluetooth.stopScan()

        assertNotNull("Light Stick 디바이스를 찾지 못했습니다", targetDevice)

        // 2. 연결
        targetDevice?.connect(
            onConnected = { ctl ->
                Log.d(TAG, "✓ Connected to ${ctl.device.mac}")
                controller = ctl
                testDevice = targetDevice
                connected = true
                connectLatch.countDown()
            },
            onFailed = { error ->
                Log.e(TAG, "✗ Connection failed: ${error.message}")
                connectLatch.countDown()
            }
        )

        connectLatch.await(15, TimeUnit.SECONDS)
        assertTrue("연결에 실패했습니다", connected)

        // 3. 색상 전송
        Log.d(TAG, "Sending colors...")

        controller?.sendColor(Colors.RED, transition = 10)
        Log.d(TAG, "  → RED")
        Thread.sleep(1000)

        controller?.sendColor(Colors.GREEN, transition = 10)
        Log.d(TAG, "  → GREEN")
        Thread.sleep(1000)

        controller?.sendColor(Colors.BLUE, transition = 10)
        Log.d(TAG, "  → BLUE")
        Thread.sleep(1000)

        Log.d(TAG, "✓ Color test passed")
    }

    @Test
    fun testSendEffects() {
        Log.d(TAG, "Starting effects test...")

        val device = findAndConnect()
        assertNotNull("디바이스 연결 실패", device)

        Log.d(TAG, "Sending effects...")

        // Blink
        controller?.sendEffect(LSEffectPayload.Effects.blink(Colors.RED, period = 5))
        Log.d(TAG, "  → BLINK (RED)")
        Thread.sleep(3000)

        // Strobe
        controller?.sendEffect(LSEffectPayload.Effects.strobe(Colors.GREEN, period = 3))
        Log.d(TAG, "  → STROBE (GREEN)")
        Thread.sleep(3000)

        // Breath
        controller?.sendEffect(LSEffectPayload.Effects.breath(Colors.BLUE, period = 15))
        Log.d(TAG, "  → BREATH (BLUE)")
        Thread.sleep(3000)

        // Off
        controller?.sendEffect(LSEffectPayload.Effects.off())
        Log.d(TAG, "  → OFF")

        Log.d(TAG, "✓ Effects test passed")
    }

    @Test
    fun testReadBattery() {
        Log.d(TAG, "Starting battery test...")

        val device = findAndConnect()
        assertNotNull("디바이스 연결 실패", device)

        val latch = CountDownLatch(1)
        var batteryLevel: Int? = null

        controller?.readBattery { result ->
            result.onSuccess { level ->
                Log.d(TAG, "🔋 Battery: $level%")
                batteryLevel = level
            }.onFailure { error ->
                Log.e(TAG, "✗ Battery read failed: ${error.message}")
            }
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        Log.d(TAG, "✓ Battery test completed (Level: ${batteryLevel ?: "N/A"})")
    }

    @Test
    fun testConnectedDevicesCount() {
        Log.d(TAG, "Starting device count test...")

        findAndConnect()

        val count = LSBluetooth.connectedCount()
        Log.d(TAG, "📊 Connected devices: $count")

        assertTrue("연결된 디바이스가 없습니다", count > 0)

        val devices = LSBluetooth.connectedDevices()
        devices.forEach { dev ->
            Log.d(TAG, "  - ${dev.mac} (${dev.name})")
        }

        Log.d(TAG, "✓ Device count test passed")
    }

    /**
     * 디바이스를 찾아서 연결
     */
    private fun findAndConnect(): Device? {
        val scanLatch = CountDownLatch(1)
        val connectLatch = CountDownLatch(1)
        var device: Device? = null

        LSBluetooth.startScan { dev ->
            if (dev.name?.endsWith("LS") == true) {
                device = dev
                scanLatch.countDown()
            }
        }

        scanLatch.await(10, TimeUnit.SECONDS)
        LSBluetooth.stopScan()

        device?.connect(
            onConnected = { ctl ->
                controller = ctl
                testDevice = device
                connectLatch.countDown()
            },
            onFailed = { error ->
                Log.e(TAG, "Connection failed: ${error.message}", error)
                connectLatch.countDown()
            }
        )

        connectLatch.await(15, TimeUnit.SECONDS)
        return device
    }
}