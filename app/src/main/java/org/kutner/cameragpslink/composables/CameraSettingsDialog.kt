package org.kutner.cameragpslink.composables

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.kutner.cameragpslink.AppSettingsManager
import org.kutner.cameragpslink.R

/** Connection strategy is automatic; per-camera settings only contain user choices. */
@SuppressLint("MissingPermission")
@Composable
fun CameraSettingsDialog(
    cameraAddress: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val context = LocalContext.current
    val currentSettings = remember { AppSettingsManager.getCameraSettings(context, cameraAddress) }
    val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    val deviceName = runCatching {
        bluetoothManager.adapter.getRemoteDevice(cameraAddress).name
    }.getOrNull() ?: context.getString(R.string.unknown_camera_name)
    var customName by remember { mutableStateOf(currentSettings.customName.orEmpty()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = context.getString(R.string.dialog_camera_settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = context.getString(R.string.dialog_camera_settings_automatic_connection),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(context.getString(R.string.dialog_camera_settings_camera_name)) },
                    placeholder = { Text(deviceName) },
                    singleLine = true,
                )
                Button(
                    onClick = { onSave(customName) },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(context.getString(R.string.button_ok))
                }
            }
        }
    }
}
