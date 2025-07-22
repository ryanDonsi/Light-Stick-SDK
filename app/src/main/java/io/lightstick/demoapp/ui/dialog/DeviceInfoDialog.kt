package io.lightstick.demoapp.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.lightstick.demoapp.ui.components.BaseDialog
import io.lightstick.sdk.ble.model.DeviceInfo

@Composable
fun DeviceInfoDialog(
    address: String,
    info: DeviceInfo?,
    onDismiss: () -> Unit
) {
    BaseDialog(
        title = "Device Info",
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                InfoRow(label = "Address", value = address)
                if(info != null) {
                    InfoRow(label = "Manufacturer", value = info.manufacturer)
                    InfoRow(label = "Model", value = info.model)
                    InfoRow(label = "Firmware", value = info.firmwareVersion)
                    InfoRow(label = "Name", value = info.deviceName)
                } else {
                    Text("정보를 가져오는 중입니다...")
                }
            }
        },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value ?: "-",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

