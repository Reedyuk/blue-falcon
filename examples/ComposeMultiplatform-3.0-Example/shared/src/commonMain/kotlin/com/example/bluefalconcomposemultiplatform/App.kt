package com.example.bluefalconcomposemultiplatform

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDrive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluefalconcomposemultiplatform.ble.presentation.BluetoothDeviceViewModel
import com.example.bluefalconcomposemultiplatform.ble.presentation.UiEvent
import com.example.bluefalconcomposemultiplatform.ble.presentation.component.DeviceDetailScreen
import com.example.bluefalconcomposemultiplatform.ble.presentation.component.DeviceScanView
import com.example.bluefalconcomposemultiplatform.core.presentation.BlueFalconTheme
import com.example.bluefalconcomposemultiplatform.di.AppModule
import dev.bluefalcon.plugins.broadcast.BroadcastState
import dev.icerock.moko.mvvm.compose.getViewModel
import dev.icerock.moko.mvvm.compose.viewModelFactory

@Composable
fun App(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    appModule: AppModule
) {
    BlueFalconTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor
    ) {
        val viewModel = getViewModel(
            key = "bluetooth-device-screen",
            factory = viewModelFactory {
                BluetoothDeviceViewModel(appModule.blueFalcon, appModule.fotaPlugin, appModule.advertiser)
            }
        )

        val state by viewModel.deviceState.collectAsState()

        Column {
            // Broadcast status banner (shown at the top of all screens when active)
            if (state.broadcastState != BroadcastState.Idle) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            when (state.broadcastState) {
                                BroadcastState.Broadcasting -> MaterialTheme.colorScheme.primaryContainer
                                BroadcastState.Error -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.BluetoothDrive,
                        contentDescription = "Broadcasting",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (state.broadcastState) {
                            BroadcastState.Starting -> "Starting broadcast…"
                            BroadcastState.Broadcasting -> "Broadcasting as cloned device"
                            BroadcastState.Error -> "Broadcast error — tap STOP to reset"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    TextButton(onClick = { viewModel.onEvent(UiEvent.OnStopBroadcast) }) {
                        Text("STOP", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

        AnimatedContent(
            targetState = state.selectedDeviceId,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it } + fadeOut())
                } else {
                    (slideInHorizontally { -it } + fadeIn()) togetherWith
                            (slideOutHorizontally { it } + fadeOut())
                }
            }
        ) { selectedDeviceId ->
            // Look up the current device from state inside the content lambda
            // This ensures we get the latest version when services are discovered
            val selectedDevice = selectedDeviceId?.let { id ->
                state.devices[id]?.takeIf { it.connected }
            }
            
            if (selectedDevice != null) {
                DeviceDetailScreen(
                    device = selectedDevice,
                    onEvent = viewModel::onEvent
                )
            } else {
                DeviceScanView(
                    state = state,
                    onEvent = viewModel::onEvent
                )
            }
        } // end AnimatedContent
        } // end Column

        // Clone result dialog (overlay, outside the main Column)
        state.cloneResultJson?.let { json ->
            AlertDialog(
                onDismissRequest = { viewModel.onEvent(UiEvent.OnDismissCloneResult) },
                title = {
                    Text(
                        text = "Device Clone Result",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = json,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    state.currentClone?.let { clone ->
                        TextButton(
                            onClick = { viewModel.onEvent(UiEvent.OnStartBroadcast(clone)) }
                        ) {
                            Text("BROADCAST")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onEvent(UiEvent.OnDismissCloneResult) }) {
                        Text("CLOSE")
                    }
                }
            )
        }
    }
}