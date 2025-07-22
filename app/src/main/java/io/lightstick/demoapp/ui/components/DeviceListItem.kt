package io.lightstick.demoapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun DeviceListItem(
    name: String,
    address: String,
    isConnected: Boolean,
    batteryLevel: Int?, // ✅ 배터리 레벨 추가
    showButtons: Boolean = true,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onColorClick: () -> Unit,
    onEffectClick: () -> Unit,
    onOtaClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    val backgroundColor = if (isConnected) Color.Blue else Color.White
    val contentColor = if (isConnected) Color.White else Color.Black
    val iconAlpha = if (isConnected) 1f else 0.3f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = name,
                    color = contentColor,
                    modifier = Modifier.weight(1f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (batteryLevel != null) {
                        Text(
                            text = "\uD83D\uDD0B $batteryLevel%",
                            color = contentColor,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }

                    Icon(
                        imageVector = if (isConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                        contentDescription = "연결 상태",
                        tint = contentColor.copy(alpha = iconAlpha),
                        modifier = Modifier.clickable {
                            if (isConnected) onDisconnectClick() else onConnectClick()
                        }
                    )
                }
            }

            Text(
                text = "MAC: $address",
                color = contentColor,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (showButtons) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    ActionButton(
                        label = "Device Info",
                        onClick = onInfoClick,
                        enabled = isConnected,
                        modifier = Modifier.weight(1f)
                    )
                    ActionButton(
                        label = "Send Color",
                        onClick = onColorClick,
                        enabled = isConnected,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    ActionButton(
                        label = "Send Payload",
                        onClick = onEffectClick,
                        enabled = isConnected,
                        modifier = Modifier.weight(1f)
                    )
                    ActionButton(
                        label = "OTA",
                        onClick = onOtaClick,
                        enabled = isConnected,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
