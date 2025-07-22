package io.lightstick.demoapp.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.lightstick.demoapp.ui.components.*
import io.lightstick.sdk.ble.model.*

@Composable
fun LSEffectPayloadDialog(
    onSend: (LSEffectPayload, Long, Boolean) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    var period by remember { mutableStateOf("10") }
    var spf by remember { mutableStateOf("100") }
    var isRandomColorEnabled by remember { mutableStateOf(false) }
    var randomDelay by remember { mutableStateOf("0") }

    val effectTypes = EffectType.entries
    var selectedEffectType by remember { mutableStateOf(EffectType.ON) }

    val intervalOptions = (100..1000 step 100).toList()
    var selectedInterval by remember { mutableStateOf(500L) }

    var selectedColor by remember { mutableStateOf(LedColor.WHITE) }
    var ledMask by remember { mutableStateOf(0x0000.toUShort()) }

    var isSequentialEffectEnabled by remember { mutableStateOf(false) }

    var isSending by remember { mutableStateOf(false) }

    BaseDialog(
        onDismissRequest = onDismiss,
        title = "Send Payload",
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp)
                    .wrapContentHeight()
            ) {
                LedMaskSelector(selectedMask = ledMask, onMaskChanged = { ledMask = it })

                ColorPalette(
                    selectedColor = selectedColor,
                    onColorSelected = { selectedColor = it }
                )

                DropdownSelector(
                    items = effectTypes.map { it.name },
                    selectedItem = selectedEffectType.name,
                    onItemSelected = { selectedEffectType = EffectType.valueOf(it) }
                )

                RowInput("Period", period) { period = it }
                RowInput("SPF", spf) { spf = it }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isRandomColorEnabled,
                        onCheckedChange = { isRandomColorEnabled = it }
                    )
                    Text("Random Color")
                }

                RowInput("Random Delay", randomDelay) { randomDelay = it }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isSequentialEffectEnabled,
                        onCheckedChange = { isSequentialEffectEnabled = it }
                    )
                    Text("Effect/Color 순차 전송")
                }

                Spacer(Modifier.height(8.dp))
                Text("⏱ 반복 간격(ms)", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                DropdownSelector(
                    items = intervalOptions.map { it.toString() },
                    selectedItem = selectedInterval.toString(),
                    onItemSelected = { selectedInterval = it.toLong() }
                )
            }
        },
        buttons = {
            TextButton(
                onClick = {
                    val payload = LSEffectPayload(
                        ledMask = ledMask,
                        color = selectedColor,
                        effectType = selectedEffectType,
                        period = period.toUByte(),
                        spf = spf.toUByte(),
                        randomColor = if (isRandomColorEnabled) 1u else 0u,
                        randomDelay = randomDelay.toUByte()
                    )
                    onSend(payload, selectedInterval, isSequentialEffectEnabled)
                    isSending = true
                },
                enabled = !isSending,
                modifier = Modifier.weight(1f)
            ) {
                Text("전송 시작")
            }

            TextButton(
                onClick = {
                    onStop()
                    isSending = false
                },
                enabled = isSending,
                modifier = Modifier.weight(1f)
            ) {
                Text("전송 중지")
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            ) {
                Text("닫기")
            }
        }
    )
}
