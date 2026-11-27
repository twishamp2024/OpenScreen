package io.github.openscreen.sender.ui.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.openscreen.sender.R

@Composable
fun ScannerScreen(
    errorText: String?,
    onCode: (String) -> Unit,
    onPaste: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraQrScanner(onText = onCode, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.scan_instructions),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(12.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.75f)
                    .aspectRatio(1f)
                    .border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(24.dp)),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (errorText != null) {
                    Text(
                        errorText,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.background(MaterialTheme.colorScheme.error, RoundedCornerShape(12.dp)).padding(12.dp),
                    )
                }
                TextButton(onClick = onPaste) { Text(stringResource(R.string.action_paste_link), color = Color.White) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel), color = Color.White) }
            }
        }
    }
}
