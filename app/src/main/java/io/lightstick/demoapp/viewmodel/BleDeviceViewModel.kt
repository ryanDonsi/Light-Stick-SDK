package io.lightstick.demoapp.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.lightstick.demoapp.ui.components.defaultLedPalette
import io.lightstick.sdk.ble.manager.*
import io.lightstick.sdk.ble.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BleDeviceViewModel @Inject constructor(
    private val scanManager: ScanManager,
    private val bondManager: BleBondManager,
    private val gattManager: BleGattManager,
    private val deviceInfoManager: DeviceInfoManager,
    private val ledControlManager: LedControlManager,
    private val otaManager: OtaManager
) : ViewModel() {

    private var effectJob: Job? = null
    private var otaTargetAddress: String = ""

    val scanResults: StateFlow<List<ScanResult>> = scanManager.scanResults

    private val _bondedDevices = MutableStateFlow<List<BondedDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BondedDevice>> = _bondedDevices

    private val _connectionState = MutableStateFlow<BleConnectionState?>(null)
    val connectionState: StateFlow<BleConnectionState?> = _connectionState

    private val _deviceInfoMap = MutableStateFlow<Map<String, DeviceInfo>>(emptyMap())
    val deviceInfoMap: StateFlow<Map<String, DeviceInfo>> = _deviceInfoMap

    private val _batteryLevels = MutableStateFlow<Map<String, Int>>(emptyMap())
    val batteryLevels: StateFlow<Map<String, Int>> = _batteryLevels

    private val _connectedAddresses = MutableStateFlow<Set<String>>(emptySet())
    val connectedAddresses: StateFlow<Set<String>> = _connectedAddresses

    private val _otaProgress = MutableStateFlow<Float?>(null)
    val otaProgress: StateFlow<Float?> = _otaProgress

    private val _otaStatus = MutableStateFlow<String>("")
    val otaStatus: StateFlow<String> = _otaStatus

    init {
        viewModelScope.launch {
            gattManager.connectionStateFlow.collect { state ->
                _connectionState.value = state
                when (state) {
                    is BleConnectionState.Connected -> {
                        _connectedAddresses.update { it + state.address }
                        readDeviceInfo(state.address)
                        readBatteryLevel(state.address)
                        enableBatteryNotification(state.address)
                    }
                    is BleConnectionState.Disconnected-> {
                        val address = state.address
                        _connectedAddresses.update { it - address }
                        _deviceInfoMap.update { it - address }
                        _batteryLevels.update { it - address }

                        if (otaTargetAddress == address) {
                            _otaProgress.value = null
                        }

                        stopEffectSending()
                    }
                    is BleConnectionState.Failed -> {
                        val address = state.address
                        _connectedAddresses.update { it - address }
                        _deviceInfoMap.update { it - address }
                        _batteryLevels.update { it - address }

                        if (otaTargetAddress == address) {
                            _otaProgress.value = null
                        }

                        stopEffectSending()
                    }
                    else -> {}
                }
            }
        }

        viewModelScope.launch {
            deviceInfoManager.deviceInfoFlow.collect { (address, info) ->
                _deviceInfoMap.update { it + (address to info) }
            }
        }

        viewModelScope.launch {
            deviceInfoManager.batteryLevelFlow.collect { (address, level) ->
                _batteryLevels.update { it + (address to level) }
            }
        }

        viewModelScope.launch {
            otaManager.otaProgressFlow.collect { pair ->
                pair?.let { (address, percent) ->
                    if (address == otaTargetAddress) {
                        _otaProgress.value = percent / 100f
                        val percent = ((_otaProgress.value ?: 0f) * 100).toInt()
                        _otaStatus.value =  "펌웨어 업그레이드 중... ($percent%)"
                    }
                }
            }
        }


        viewModelScope.launch {
            otaManager.otaStateFlow.collect { pair ->
                pair?.let { (address, state) ->
                    if (address == otaTargetAddress) {
                        _otaStatus.value = when (state) {
                            OtaState.Completed -> {
                                _otaProgress.value = null
                                "펌웨어 업그레이드 성공"
                            }
                            OtaState.Failed -> {
                                _otaProgress.value = null
                                "펌웨어 업그레이드 실패"
                            }
                            OtaState.InProgress -> {
                                "펌웨어 업그레이드 중..."
                            }
                            OtaState.Idle -> "준비"
                        }
                    }
                }
            }
        }

        refreshBondedDevices()
    }

    fun startScan() {
        try {
            scanManager.startScan()
        } catch (e: SecurityException) {
            Log.e("BleDeviceViewModel", "startScan() failed: No BLUETOOTH_SCAN permission", e)
        }
    }

    fun stopScan() {
        try {
            scanManager.stopScan()
        } catch (e: SecurityException) {
            Log.e("BleDeviceViewModel", "stopScan() failed", e)
        }
    }

    fun refreshBondedDevices() {
        try {
            val list = bondManager.getBondedDevices().map { device ->
                val name = try { device.name ?: "Unknown" } catch (e: SecurityException) { "권한 없음" }
                BondedDevice(address = device.address, name = name)
            }
            _bondedDevices.value = list
        } catch (e: SecurityException) {
            Log.e("BleDeviceViewModel", "Failed to load bonded devices", e)
        }
    }

    fun connect(address: String) {
        try {
            gattManager.connect(address)
        } catch (e: SecurityException) {
            Log.e("BleDeviceViewModel", "connect() failed: $address", e)
        }
    }

    fun disconnect(address: String) {
        try {
            gattManager.disconnect(address)
        } catch (e: SecurityException) {
            Log.e("BleDeviceViewModel", "disconnect() failed: $address", e)
        }
    }

    fun isConnected(address: String): Boolean {
        return connectedAddresses.value.contains(address)
    }

    fun autoConnectBondedDevices() {
        viewModelScope.launch {
            val bonded = try {
                bondManager.getBondedDevices()
            } catch (e: SecurityException) {
                Log.e("BleDeviceViewModel", "autoConnectBondedDevices() failed", e)
                emptyList()
            }

            bonded.forEach { device ->
                val address = device.address
                if (!isConnected(address)) {
                    try {
                        gattManager.connect(address, autoConnect = true)
                    } catch (e: SecurityException) {
                        Log.e("BleDeviceViewModel", "AutoConnect failed: $address", e)
                    }
                }
            }
        }
    }

    fun readDeviceInfo(address: String) {
        viewModelScope.launch {
            try {
                val result = deviceInfoManager.readDeviceInfo(address)
                if (result is GattOperationResult.Failure) {
                    Log.e("BleDeviceViewModel", "readDeviceInfo() failed: $result")
                }
            } catch (e: SecurityException) {
                Log.e("BleDeviceViewModel", "readDeviceInfo() failed: $address", e)
            }
        }
    }

    fun readBatteryLevel(address: String) {
        viewModelScope.launch {
            try {
                val result = deviceInfoManager.readBatteryLevel(address)
                if (result is GattOperationResult.Failure) {
                    Log.e("BleDeviceViewModel", "readBatteryLevel() failed: $result")
                }
            } catch (e: SecurityException) {
                Log.e("BleDeviceViewModel", "readBatteryLevel() failed: $address", e)
            }
        }
    }

    fun enableBatteryNotification(address: String) {
        try {
            val result = deviceInfoManager.enableBatteryNotification(address)
            if (result is GattOperationResult.Failure) {
                Log.e("BleDeviceViewModel", "enableBatteryNotification() failed: $result")
            }
        } catch (e: SecurityException) {
            Log.e("BleDeviceViewModel", "enableBatteryNotification() failed: $address", e)
        }
    }

    fun sendLedColor(address: String, color: LedColor, transition: Byte = 0) {
        try {
            val result = ledControlManager.sendLedColor(address, color, transition)
            if (result is GattOperationResult.Failure) {
                Log.e("BleDeviceViewModel", "sendLedColor() failed: $result")
            }
        } catch (e: SecurityException) {
            Log.e("BleDeviceViewModel", "sendLedColor() failed: $address", e)
        }
    }

    fun sendLedEffect(address: String, payload: LSEffectPayload) {
        try {
            val result = ledControlManager.sendLedEffect(address, payload)
            if (result is GattOperationResult.Failure) {
                Log.e("BleDeviceViewModel", "sendLedEffect() failed: $result")
            }
        } catch (e: SecurityException) {
            Log.e("BleDeviceViewModel", "sendLedEffect() failed: $address", e)
        }
    }

    fun sendEffectRepeatedly(address: String, payload: LSEffectPayload, intervalMs: Long) {
        stopEffectSending()
        effectJob = viewModelScope.launch {
            while (true) {
                try {
                    val result = ledControlManager.sendLedEffect(address, payload)
                    if (result is GattOperationResult.Failure) {
                        Log.e("BleDeviceViewModel", "sendEffectRepeatedly() failed: $result")
                        break
                    }
                } catch (e: SecurityException) {
                    Log.e("BleDeviceViewModel", "sendEffectRepeatedly() failed: $address", e)
                }
                delay(intervalMs)
            }
        }
    }

    fun sendEffectSequentially(address: String, basePayload: LSEffectPayload, intervalMs: Long) {
        effectJob?.cancel()
        effectJob = viewModelScope.launch {
            val allEffects = EffectType.entries.filter { it != EffectType.OFF }

            // ⚠️ Black 제거
            val allColors = defaultLedPalette
                .filterNot { it.red == 0.toUByte() && it.green == 0.toUByte() && it.blue == 0.toUByte() }

            var effectIndex = 0
            var colorIndex = 0

            while (true) {
                val updatedPayload = basePayload.copy(
                    effectType = allEffects[effectIndex % allEffects.size],
                    color = allColors[colorIndex % allColors.size]
                )
                sendLedEffect(address, updatedPayload)

                effectIndex++
                colorIndex++
                delay(intervalMs)
            }
        }
    }


    fun stopEffectSending() {
        effectJob?.cancel()
        effectJob = null
    }

    fun startOta(address: String, otaData: ByteArray) {
        otaTargetAddress = address
        _otaStatus.value = "시작"
        viewModelScope.launch {
            try {
                val result = otaManager.sendOtaFirmware(address, otaData)
                if (result is GattOperationResult.Failure) {
                    _otaStatus.value = "실패: ${result.reason}"
                }
            } catch (e: SecurityException) {
                _otaStatus.value = "권한 오류: ${e.message}"
            } catch (e: Exception) {
                _otaStatus.value = "예외 발생: ${e.message}"
            }
        }
    }

    fun clearOtaStatus() {
        _otaStatus.value = ""
        _otaProgress.value = null
    }

}

