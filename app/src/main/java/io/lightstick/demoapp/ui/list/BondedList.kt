package io.lightstick.demoapp.ui.list

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.lightstick.demoapp.ui.components.DeviceListItem
import io.lightstick.sdk.ble.model.BondedDevice
import io.lightstick.sdk.ble.model.DeviceInfo

@Composable
fun BondedList(
    devices: List<BondedDevice>,
    connectedAddresses: Set<String>,
    deviceInfoMap: Map<String, DeviceInfo>,
    batteryLevel: Map<String, Int>,
    onConnectClick: (String) -> Unit,
    onDisconnectClick: (String) -> Unit,
    onColorClick: (String) -> Unit,
    onEffectClick: (String) -> Unit,
    onOtaClick: (String) -> Unit,
    onInfoClick: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(devices, key = { it.address }) { device ->
            val isConnected = connectedAddresses.contains(device.address)
            val info = deviceInfoMap[device.address]
            val batteryLevel = batteryLevel[device.address]

            DeviceListItem(
                name = device.name ?: "Unknown",
                address = device.address,
                isConnected = isConnected,
                batteryLevel = batteryLevel,
                showButtons = true,
                onConnectClick = { onConnectClick(device.address) },
                onDisconnectClick = { onDisconnectClick(device.address) },
                onColorClick = { onColorClick(device.address) },
                onEffectClick = { onEffectClick(device.address) },
                onOtaClick = { onOtaClick(device.address) },
                onInfoClick = { onInfoClick(device.address) }
            )
        }
    }
}
