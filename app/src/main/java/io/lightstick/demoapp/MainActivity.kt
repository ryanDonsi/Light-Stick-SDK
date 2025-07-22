package io.lightstick.demoapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.android.AndroidEntryPoint
import io.lightstick.demoapp.ui.BleTabbedScreen
import io.lightstick.demoapp.ui.theme.LightStickTheme
import io.lightstick.sdk.ble.manager.BleBondManager
import jakarta.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var bleBondManager: BleBondManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 필요 시 권한 처리 결과 로깅
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // BLE 자동 연결 및 수신기 등록
        bleBondManager.registerReceiver()

        try {
            bleBondManager.autoConnectBondedDevices()
        } catch (e: SecurityException) {
            Log.e("MainActivity", "❌ autoConnect 실패", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }

        setContent {
            LightStickTheme {
                BleTabbedScreen()
            }
        }
    }

    override fun onDestroy() {
        bleBondManager.unregisterReceiver()
        super.onDestroy()
    }
}
