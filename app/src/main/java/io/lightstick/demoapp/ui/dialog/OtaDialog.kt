package io.lightstick.demoapp.ui.dialog

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.lightstick.demoapp.ui.components.BaseDialog

@Composable
fun OtaDialog(
    selectedFileName: String?,
    progress: Float?,
    statusText: String?,
    onDismiss: () -> Unit,
    onPickFile: () -> Unit,
    onStartOta: () -> Unit
) {
    BaseDialog(
        onDismissRequest = onDismiss,
        title = "펌웨어 업데이트",
        dismissOnClickedOutside = false,
        content = {
            Text(
                text = "파일: ${selectedFileName ?: "선택되지 않음"}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            //if (!statusText.isNullOrBlank()) {
            Text(text = statusText?:"")
            Spacer(Modifier.height(8.dp))
            //}

            // 항상 표시되도록 보장하며, null-safe 처리
            LinearProgressIndicator(
                progress = { progress ?: 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.LightGray
            )
        },
        buttons = {
            TextButton(onClick = onPickFile, enabled = progress == null) {
                Text("파일 선택")
            }
            TextButton(onClick = onStartOta, enabled = progress == null && selectedFileName != null) {
                Text("업데이트 시작")
            }
            TextButton(onClick = onDismiss, enabled = progress == null) {
                Text("닫기")
            }
        }
    )
}
