package io.lightstick.demoapp.ui.dialog

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import io.lightstick.demoapp.ui.components.BaseDialog
import io.lightstick.demoapp.ui.components.ColorPalette
import io.lightstick.demoapp.ui.components.RowInput
import io.lightstick.sdk.ble.model.LedColor

@Composable
fun ColorPickerDialog(
    onDismiss: () -> Unit,
    onColorSelected: (LedColor, Byte) -> Unit
) {
    var selectedColor by remember { mutableStateOf(LedColor.WHITE) }
    var transit by remember { mutableStateOf("0") }

    BaseDialog(
        onDismissRequest = onDismiss,
        title = "Send Color",
        content = {
            ColorPalette(
                selectedColor = selectedColor,
                onColorSelected = {
                    selectedColor = it
                    onColorSelected(
                        selectedColor,
                        transit.toIntOrNull()?.toByte() ?: 0
                    )
                }
            )

            RowInput(
                label = "transit",
                value = transit,
                onValueChange = { transit = it }
            )
        },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        }
    )
}
