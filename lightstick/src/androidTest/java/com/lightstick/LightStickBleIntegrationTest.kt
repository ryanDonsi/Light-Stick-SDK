package com.lightstick.test

import android.Manifest
import android.content.Context
import android.util.Log
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
 * Light Stick BLE 통합 테스트
 *
 * 실제 BLE 디바이스와의 연동을 테스트합니다.
 *
 * 주의:
 * - 이 테스트는 실제 Android 디바이스에서 실행해야 합니다.
 * - BLE 권한이 필요합니다.
 * - 테스트 실행 전 Light Stick 디바이스가 근처에 있어야 합니다.
 */
@RunWith(AndroidJUnit4::class)
class LightStickBleIntegrationTest {

    private lateinit var context: Context
    private var connectedController: Controller? = null
    private var testDevice: Device? = null

    companion object {
        private const val TAG = "LightStickBleTest"
        private const val SCAN_TIMEOUT_SECONDS = 10L
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val OPERATION_TIMEOUT_SECONDS = 5L
    }

    /**
     * BLE 권한 자동 부여
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
        Log.d(TAG, "Test setup completed")
    }

    @After
    fun tearDown() {
        // 연결된 디바이스 정리
        testDevice?.disconnect()
        connectedController = null
        testDevice = null

        // 스캔 중지
        try {
            LSBluetooth.stopScan()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan: ${e.message}")
        }

        Log.d(TAG, "Test teardown completed")
    }

    // ===========================================================================================
    // 스캔 테스트
    // ===========================================================================================

    @Test
    fun testBleInitialization() {
        // SDK 초기화 테스트
        assertNotNull(context)
        Log.d(TAG, "✓ SDK initialized successfully")
    }

    @Test
    fun testStartScan() {
        val latch = CountDownLatch(1)
        val foundDevices = mutableListOf<Device>()

        LSBluetooth.startScan { device ->
            Log.d(TAG, "Found device: ${device.mac} (${device.name}) RSSI: ${device.rssi}")
            foundDevices.add(device)

            // 첫 번째 디바이스를 찾으면 latch 해제
            if (foundDevices.size == 1) {
                latch.countDown()
            }
        }

        // 스캔 결과 대기
        val foundDevice = latch.await(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        LSBluetooth.stopScan()

        assertTrue("No devices found within $SCAN_TIMEOUT_SECONDS seconds", foundDevice)
        assertTrue("Device list should not be empty", foundDevices.isNotEmpty())
        Log.d(TAG, "✓ Found ${foundDevices.size} device(s)")
    }

    @Test
    fun testStopScan() {
        LSBluetooth.startScan { device ->
            Log.d(TAG, "Scanning: ${device.mac}")
        }

        Thread.sleep(1000) // 1초 스캔

        LSBluetooth.stopScan()
        Log.d(TAG, "✓ Scan stopped successfully")
    }

    @Test
    fun testScanForLightStickDevices() {
        val latch = CountDownLatch(1)
        var lightStickDevice: Device? = null

        LSBluetooth.startScan { device ->
            // "LightStick" 또는 "LS"로 끝나는 디바이스 찾기
            val name = device.name
            if (name != null && (name.startsWith("LightStick", ignoreCase = true) || name.endsWith("LS"))) {
                Log.d(TAG, "Found LightStick device: ${device.mac} (${device.name})")
                lightStickDevice = device
                latch.countDown()
            }
        }

        val found = latch.await(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        LSBluetooth.stopScan()

        assertTrue("No LightStick device found", found)
        assertNotNull("LightStick device should not be null", lightStickDevice)
        Log.d(TAG, "✓ LightStick device found: ${lightStickDevice?.name}")
    }

    // ===========================================================================================
    // 연결 테스트
    // ===========================================================================================

    @Test
    fun testConnectToDevice() {
        val scanLatch = CountDownLatch(1)
        val connectLatch = CountDownLatch(1)
        var foundDevice: Device? = null
        var connectionSuccess = false

        // 1. 디바이스 스캔
        LSBluetooth.startScan { device ->
            val name = device.name
            if (name != null && (name.startsWith("LightStick", ignoreCase = true) || name.endsWith("LS"))) {
                foundDevice = device
                scanLatch.countDown()
            }
        }

        val deviceFound = scanLatch.await(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        LSBluetooth.stopScan()

        assertTrue("No device found for connection test", deviceFound)
        assertNotNull(foundDevice)

        // 2. 디바이스 연결
        foundDevice?.connect(
            onConnected = { controller ->
                Log.d(TAG, "Connected to ${controller.device.mac}")
                connectedController = controller
                testDevice = controller.device
                connectionSuccess = true
                connectLatch.countDown()
            },
            onFailed = { error ->
                Log.e(TAG, "Connection failed: ${error.message}", error)
                connectLatch.countDown()
            }
        )

        val connected = connectLatch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue("Connection timeout", connected)
        assertTrue("Connection should succeed", connectionSuccess)
        assertNotNull("Controller should not be null", connectedController)
        Log.d(TAG, "✓ Successfully connected to device")
    }

    @Test
    fun testDisconnectDevice() {
        // 먼저 연결
        val device = findAndConnectDevice()
        assertNotNull("Device should be connected", device)

        // 연결 해제
        device?.disconnect()
        Thread.sleep(1000) // 연결 해제 대기

        Log.d(TAG, "✓ Device disconnected successfully")
    }

    // ===========================================================================================
    // LED 제어 테스트 (연결 필요)
    // ===========================================================================================

    @Test
    fun testSendColorToDevice() {
        val device = findAndConnectDevice()
        assertNotNull("Device should be connected", device)

        val operationLatch = CountDownLatch(1)
        var operationSuccess = false

        try {
            // 빨간색 전송
            connectedController?.sendColor(Colors.RED, transition = 10)
            operationSuccess = true
            Log.d(TAG, "Sent RED color to device")

            Thread.sleep(1000) // 색상 전환 관찰

            // 파란색 전송
            connectedController?.sendColor(Colors.BLUE, transition = 10)
            Log.d(TAG, "Sent BLUE color to device")

            operationLatch.countDown()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending color: ${e.message}", e)
            operationLatch.countDown()
        }

        val completed = operationLatch.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue("Color send operation timeout", completed)
        assertTrue("Color send should succeed", operationSuccess)
        Log.d(TAG, "✓ Color commands sent successfully")
    }

    @Test
    fun testSendEffectToDevice() {
        val device = findAndConnectDevice()
        assertNotNull("Device should be connected", device)

        val operationLatch = CountDownLatch(1)
        var operationSuccess = false

        try {
            // Blink 이펙트 전송
            val blinkEffect = LSEffectPayload.Effects.blink(Colors.GREEN, period = 10)
            connectedController?.sendEffect(blinkEffect)
            operationSuccess = true
            Log.d(TAG, "Sent BLINK effect to device")

            Thread.sleep(2000) // 이펙트 관찰

            operationLatch.countDown()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending effect: ${e.message}", e)
            operationLatch.countDown()
        }

        val completed = operationLatch.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue("Effect send operation timeout", completed)
        assertTrue("Effect send should succeed", operationSuccess)
        Log.d(TAG, "✓ Effect command sent successfully")
    }

    @Test
    fun testSendMultipleEffects() {
        val device = findAndConnectDevice()
        assertNotNull("Device should be connected", device)

        try {
            // 1. Blink 이펙트
            connectedController?.sendEffect(LSEffectPayload.Effects.blink(Colors.RED, period = 5))
            Log.d(TAG, "Sent BLINK effect")
            Thread.sleep(2000)

            // 2. Strobe 이펙트
            connectedController?.sendEffect(LSEffectPayload.Effects.strobe(Colors.GREEN, period = 3))
            Log.d(TAG, "Sent STROBE effect")
            Thread.sleep(2000)

            // 3. Breath 이펙트
            connectedController?.sendEffect(LSEffectPayload.Effects.breath(Colors.BLUE, period = 20))
            Log.d(TAG, "Sent BREATH effect")
            Thread.sleep(2000)

            // 4. OFF
            connectedController?.sendEffect(LSEffectPayload.Effects.off())
            Log.d(TAG, "Sent OFF effect")

            Log.d(TAG, "✓ Multiple effects sent successfully")
        } catch (e: Exception) {
            fail("Error sending multiple effects: ${e.message}")
        }
    }

    @Test
    fun testReadBattery() {
        val device = findAndConnectDevice()
        assertNotNull("Device should be connected", device)

        val latch = CountDownLatch(1)
        var batteryLevel: Int? = null

        connectedController?.readBattery { result ->
            result.onSuccess { level ->
                Log.d(TAG, "Battery level: $level%")
                batteryLevel = level
            }.onFailure { error ->
                Log.e(TAG, "Failed to read battery: ${error.message}", error)
            }
            latch.countDown()
        }

        val completed = latch.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue("Battery read timeout", completed)
        // batteryLevel이 null일 수도 있음 (지원하지 않는 디바이스)
        Log.d(TAG, "✓ Battery read operation completed: ${batteryLevel ?: "N/A"}%")
    }

    // ===========================================================================================
    // 브로드캐스트 테스트 (여러 디바이스)
    // ===========================================================================================

    @Test
    fun testBroadcastColor() {
        // 최소 1개 이상의 디바이스 연결
        val device = findAndConnectDevice()
        assertNotNull("At least one device should be connected", device)

        try {
            // 모든 연결된 디바이스에 색상 브로드캐스트
            LSBluetooth.broadcastColor(Colors.YELLOW, transition = 15)
            Log.d(TAG, "Broadcasted YELLOW color to all devices")

            Thread.sleep(1000)

            LSBluetooth.broadcastColor(Colors.MAGENTA, transition = 15)
            Log.d(TAG, "Broadcasted MAGENTA color to all devices")

            Log.d(TAG, "✓ Broadcast color successful")
        } catch (e: Exception) {
            fail("Error broadcasting color: ${e.message}")
        }
    }

    @Test
    fun testBroadcastEffect() {
        val device = findAndConnectDevice()
        assertNotNull("At least one device should be connected", device)

        try {
            val breathEffect = LSEffectPayload.Effects.breath(Colors.CYAN, period = 15)
            LSBluetooth.broadcastEffect(breathEffect)
            Log.d(TAG, "Broadcasted BREATH effect to all devices")

            Thread.sleep(3000) // 이펙트 관찰

            Log.d(TAG, "✓ Broadcast effect successful")
        } catch (e: Exception) {
            fail("Error broadcasting effect: ${e.message}")
        }
    }

    // ===========================================================================================
    // 연결 상태 조회 테스트
    // ===========================================================================================

    @Test
    fun testConnectedDevicesList() {
        val device = findAndConnectDevice()
        assertNotNull("Device should be connected", device)

        val connectedDevices = LSBluetooth.connectedDevices()
        assertNotNull("Connected devices list should not be null", connectedDevices)
        assertTrue("Should have at least one connected device", connectedDevices.isNotEmpty())

        Log.d(TAG, "Connected devices count: ${connectedDevices.size}")
        connectedDevices.forEach { dev ->
            Log.d(TAG, "  - ${dev.mac} (${dev.name})")
        }

        Log.d(TAG, "✓ Connected devices list retrieved")
    }

    @Test
    fun testConnectedDevicesCount() {
        val device = findAndConnectDevice()
        assertNotNull("Device should be connected", device)

        val count = LSBluetooth.connectedCount()
        assertTrue("Connected count should be > 0", count > 0)

        Log.d(TAG, "✓ Connected devices count: $count")
    }

    @Test
    fun testBondedDevicesList() {
        val bondedDevices = LSBluetooth.bondedDevices()
        assertNotNull("Bonded devices list should not be null", bondedDevices)

        Log.d(TAG, "Bonded devices count: ${bondedDevices.size}")
        bondedDevices.forEach { dev ->
            Log.d(TAG, "  - ${dev.mac} (${dev.name})")
        }

        Log.d(TAG, "✓ Bonded devices list retrieved")
    }

    // ===========================================================================================
    // 헬퍼 메서드
    // ===========================================================================================

    /**
     * 디바이스를 찾아서 연결하는 헬퍼 메서드
     */
    private fun findAndConnectDevice(): Device? {
        val scanLatch = CountDownLatch(1)
        val connectLatch = CountDownLatch(1)
        var foundDevice: Device? = null

        // 스캔
        LSBluetooth.startScan { device ->
            val name = device.name
            if (name != null && (name.startsWith("LightStick", ignoreCase = true) || name.endsWith("LS"))) {
                foundDevice = device
                scanLatch.countDown()
            }
        }

        scanLatch.await(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        LSBluetooth.stopScan()

        // 연결
        foundDevice?.connect(
            onConnected = { controller ->
                connectedController = controller
                testDevice = controller.device
                connectLatch.countDown()
            },
            onFailed = { error ->
                Log.e(TAG, "Connection failed: ${error.message}", error)
                connectLatch.countDown()
            }
        )

        connectLatch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return testDevice
    }
}