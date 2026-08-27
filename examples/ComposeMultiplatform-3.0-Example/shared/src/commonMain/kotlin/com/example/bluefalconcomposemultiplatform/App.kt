package com.example.bluefalconcomposemultiplatform

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluefalconcomposemultiplatform.ble.presentation.BluetoothDeviceState
import com.example.bluefalconcomposemultiplatform.ble.presentation.BluetoothDeviceViewModel
import com.example.bluefalconcomposemultiplatform.ble.presentation.UiEvent
import com.example.bluefalconcomposemultiplatform.ble.presentation.component.DeviceDetailScreen
import com.example.bluefalconcomposemultiplatform.ble.presentation.component.DeviceScanView
import com.example.bluefalconcomposemultiplatform.core.presentation.BlueFalconTheme
import com.example.bluefalconcomposemultiplatform.di.AppModule
import com.example.bluefalconcomposemultiplatform.peripheral.presentation.PeripheralServerView
import com.example.bluefalconcomposemultiplatform.peripheral.presentation.PeripheralServerViewModel
import dev.bluefalcon.plugins.broadcast.BroadcastState
import dev.icerock.moko.mvvm.compose.getViewModel
import dev.icerock.moko.mvvm.compose.viewModelFactory

private enum class ExampleMode(
    val label: String,
) {
    Central("Central"),
    Peripheral("Peripheral"),
}

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
                BluetoothDeviceViewModel(appModule.blueFalcon, appModule.fotaPlugin, appModule.bondingPlugin, appModule.advertiser)
            }
        )

        var selectedMode by remember { mutableStateOf(ExampleMode.Central) }
        var peripheralViewModelInitialized by remember { mutableStateOf(false) }
        val peripheralViewModel = if (peripheralViewModelInitialized) {
            getViewModel(
                key = "peripheral-server-screen",
                factory = viewModelFactory {
                    PeripheralServerViewModel(appModule.peripheralRuntime)
                },
            )
        } else {
            null
        }

        Column(modifier = Modifier.fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                ExampleMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = selectedMode == mode,
                        onClick = {
                            if (mode == ExampleMode.Peripheral) {
                                peripheralViewModelInitialized = true
                            }
                            selectedMode = mode
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ExampleMode.entries.size,
                        ),
                        label = { Text(mode.label) },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (selectedMode) {
                    ExampleMode.Central -> {
                        val state by viewModel.deviceState.collectAsState()
                        CentralContent(
                            state = state,
                            onEvent = viewModel::onEvent,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    ExampleMode.Peripheral -> {
                        PeripheralServerView(
                            viewModel = checkNotNull(peripheralViewModel) {
                                "Peripheral mode must initialize its ViewModel"
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CentralContent(
    state: BluetoothDeviceState,
    onEvent: (UiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Column {
            if (state.broadcastState != BroadcastState.Idle) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            when (state.broadcastState) {
                                BroadcastState.Broadcasting ->
                                    MaterialTheme.colorScheme.primaryContainer
                                BroadcastState.Error ->
                                    MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.BluetoothDrive,
                        contentDescription = "Broadcasting",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    TextButton(onClick = { onEvent(UiEvent.OnStopBroadcast) }) {
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
                },
            ) { selectedDeviceId ->
                val selectedDevice = selectedDeviceId?.let { id ->
                    state.devices[id]?.takeIf { it.connected }
                }

                if (selectedDevice != null) {
                    DeviceDetailScreen(
                        device = selectedDevice,
                        onEvent = onEvent,
                    )
                } else {
                    DeviceScanView(
                        state = state,
                        onEvent = onEvent,
                    )
                }
            }
        }

        state.cloneResultJson?.let { json ->
            AlertDialog(
                onDismissRequest = { onEvent(UiEvent.OnDismissCloneResult) },
                title = {
                    Text(
                        text = "Device Clone Result",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = json,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    state.currentClone?.let { clone ->
                        TextButton(
                            onClick = { onEvent(UiEvent.OnStartBroadcast(clone)) },
                        ) {
                            Text("BROADCAST")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onEvent(UiEvent.OnDismissCloneResult) }) {
                        Text("CLOSE")
                    }
                },
            )
        }
    }
}
