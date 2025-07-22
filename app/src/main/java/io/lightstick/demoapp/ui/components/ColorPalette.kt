package io.lightstick.demoapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.lightstick.sdk.ble.model.LedColor

@Composable
fun ColorPalette(
    paletteColors: List<LedColor> = defaultLedPalette,
    selectedColor: LedColor,
    onColorSelected: (LedColor) -> Unit
) {
    val rows = paletteColors.chunked(8)

    rows.forEach { rowColors ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val itemModifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .sizeIn(maxWidth = 48.dp)
            rowColors.forEach { color ->
                val isSelected = selectedColor == color
                Box(
                    modifier = itemModifier
                        .background(
                            color = Color(color.red.toInt(), color.green.toInt(), color.blue.toInt()),
                            shape = MaterialTheme.shapes.small
                        )
                        .clickable { onColorSelected(color) }
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(20.dp)
                                .background(
                                    color = Color.Black.copy(alpha = 0.4f),
                                    shape = MaterialTheme.shapes.small
                                )
                        )
                    }
                }
            }

            if (rowColors.size < 4) {
                repeat(4 - rowColors.size) {
                    Spacer(modifier = itemModifier)
                }
            }
        }
    }
}

val defaultLedPalette = listOf(
    LedColor(255, 0, 0), LedColor(0, 255, 0), LedColor(0, 0, 255),
    LedColor(255, 255, 0), LedColor(0, 255, 255), LedColor(255, 0, 255),
    LedColor(255, 255, 255), LedColor(0, 0, 0)
)
