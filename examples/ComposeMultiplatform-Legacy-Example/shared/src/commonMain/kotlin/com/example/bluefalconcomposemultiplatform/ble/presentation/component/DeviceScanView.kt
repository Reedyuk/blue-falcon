package com.example.bluefalconcomposemultiplatform.ble.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluefalconcomposemultiplatform.ble.presentation.BluetoothDeviceState
import com.example.bluefalconcomposemultiplatform.ble.presentation.UiEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScanView(
    state: BluetoothDeviceState,
    onEvent: (UiEvent) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                            contentDescription = "Bluetooth",
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(
                            text = "  Scanner",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                actions = {
                    if (state.isScanning) {
                        OutlinedButton(
                            onClick = { onEvent(UiEvent.OnStopScanClick) },
                            modifier = Modifier.padding(end = 8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("STOP")
                        }
                    } else {
                        Button(
                            onClick = { onEvent(UiEvent.OnScanClick) },
                            modifier = Modifier.padding(end = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onPrimary,
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("SCAN")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (state.devices.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                        contentDescription = "No devices",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (state.isScanning) "Scanning for devices..." else "Press SCAN to find nearby devices",
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 16.sp
                    )
                }
            } else {
                Text(
                    text = "DISCOVERED DEVICES (${state.devices.size})",
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        state.devices.values.toList().sortedByDescending { it.peripheral.rssi }
                    ) { device ->
                        @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
                        ScanResultCard(
                            deviceName = device.peripheral.name,
                            macId = device.peripheral.uuid,
                            rssi = device.peripheral.rssi,
                            connected = device.connected,
                            onConnect = { onEvent(UiEvent.OnConnectClick(device.peripheral.uuid)) },
                            onSelect = { onEvent(UiEvent.OnDeviceSelected(device.peripheral.uuid)) }
                        )
                    }
                }
            }
        }
    }
}