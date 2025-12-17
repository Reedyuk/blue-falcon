package com.example.bluefalconcomposemultiplatform.ble.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bluefalconcomposemultiplatform.ble.presentation.BluetoothDeviceState
import com.example.bluefalconcomposemultiplatform.ble.presentation.UiEvent

@Composable
fun DeviceScanView(
    state: BluetoothDeviceState,
    onEvent: (UiEvent) -> Unit
) {
    Button(
        onClick = {
            onEvent(UiEvent.OnScanClick)
        },
        modifier = Modifier
            .width(140.dp)
            .padding(start = 20.dp, top = 20.dp)
    ) {
        if(state.isScanning) {
            Text("Stop Scanning")
        } else {
            Text("Scan")
        }
    }
    Column(
        modifier = Modifier
            .padding(20.dp)
            .fillMaxWidth()
            .fillMaxHeight()
            .background(
                MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(10.dp),
    ) {
        LazyColumn {
            items(state.devices.values.toList().sortedByDescending { it.peripheral.name }) { device ->
                @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
                FoundDeviceCard(
                    deviceName = if (!device.peripheral.name.isNullOrBlank()) device.peripheral.name else "No Name",
                    macId = device.peripheral.uuid,
                    advertisementInfo = device.advertisementInfo,
                    rssi = device.peripheral.rssi,
                    services = device.peripheral.services,
                    connected = device.connected,
                    onEvent = onEvent,
                    showFullDetails = device.showDetails
                )
            }
        }
    }
}