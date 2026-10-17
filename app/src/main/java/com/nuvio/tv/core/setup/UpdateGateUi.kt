package com.nuvio.tv.core.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Result of the forced-update gate evaluation. Shared by the full
 * implementation and the playstore no-op stub so MainActivity (main source
 * set) can depend on one type.
 */
data class UpdateGateState(
    val checked: Boolean = false,
    val blocked: Boolean = false,
    val message: String = "Nubio requires an update to continue."
)

/**
 * Non-dismissable blocking dialog shown when the forced-update gate blocks
 * app startup. A single button drives the existing in-app updater flow.
 */
@Composable
fun UpdateGateBlockedDialog(
    message: String,
    isDownloading: Boolean,
    downloadProgress: Float?,
    onUpdateNow: () -> Unit
) {
    Dialog(
        onDismissRequest = { /* non-dismissable */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Column(
            modifier = Modifier
                .background(Color.Black)
                .padding(32.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Update required",
                color = Color.White,
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = Color(0xFFB0B0B0),
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            if (isDownloading) {
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress ?: 0f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onUpdateNow) {
                Text(text = "Update Now")
            }
        }
    }
}
