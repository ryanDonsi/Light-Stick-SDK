package io.lightstick.demoapp.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.layout.FlowRow

@Composable
fun LedMaskSelector(
    selectedMask: UShort,
    onMaskChanged: (UShort) -> Unit
) {
    var allSelected by remember { mutableStateOf(selectedMask == 0x0000.toUShort()) }

    val selectedBits: SnapshotStateList<Int> = remember(selectedMask) {
        val list = mutableListOf<Int>()
        if (selectedMask != 0x0000.toUShort()) {
            for (bit in 0..15) {
                if (selectedMask.toInt() and (1 shl bit) != 0) {
                    list.add(bit)
                }
            }
        }
        mutableStateListOf(*list.toTypedArray())
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Checkbox(
                checked = allSelected,
                onCheckedChange = {
                    allSelected = it
                    if (it) {
                        selectedBits.clear()
                        onMaskChanged(0x0000.toUShort())
                    }
                }
            )
            Text("All LEDs (0x0000)")
        }

        if (!allSelected) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                (0..15).forEach { bit ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectedBits.contains(bit),
                            onCheckedChange = { checked ->
                                if (checked) selectedBits.add(bit)
                                else selectedBits.remove(bit)

                                val mask = selectedBits.fold(0) { acc, b -> acc or (1 shl b) }
                                onMaskChanged(mask.toUShort())
                            }
                        )
                        Text(" $bit")
                    }
                }
            }
        }
    }
}
