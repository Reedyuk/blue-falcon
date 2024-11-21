package com.example.bluefalconcomposemultiplatform.ble.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluefalconcomposemultiplatform.ble.presentation.UiEvent
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothService

@Composable
fun FoundDeviceCard(
    deviceName: String?,
    macId: String,
    rssi: Float?,
    services: List<BluetoothService>,
    connected: Boolean,
    onEvent: (UiEvent) -> Unit
) {
    Row (
        modifier = Modifier
            .padding(10.dp)
            .fillMaxWidth()
            .wrapContentHeight()
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(10.dp)
            )
            .background(
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(10.dp)
            ),

        ) {
        Row(
            modifier = Modifier
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "NAME: $deviceName",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 12.sp
                )
                rssi?.let {
                    Text(
                        text = "RSSI: $rssi",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 8.sp
                    )
                }
                Column {
                    Text(
                        "Services",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 8.sp
                    )
                    services.forEach {
                        ServiceRow(macId, it, onEvent)
                    }
                }
            }
            if (!connected) {
                Button(
                    onClick = {
                        onEvent(UiEvent.OnConnectClick(macId))
                    }
                ) {
                    Text("Connect")
                }
            } else {
                Button(
                    onClick = {
                        onEvent(UiEvent.OnDisconnectClick(macId))
                    }
                ) {
                    Text("Disconnect")
                }
            }

        }
    }
}

@Composable
fun ServiceRow(
    macId: String,
    service: BluetoothService,
    onEvent: (UiEvent) -> Unit
) {
    Column {
        Text(
            "Name: ${service.name}",
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 6.sp
        )
        Column {
            service.characteristics.forEach {
                CharacteristicsRow(macId, it, onEvent)
            }
        }
    }
}

@Composable
fun CharacteristicsRow(
    macId: String,
    characteristic: BluetoothCharacteristic,
    onEvent: (UiEvent) -> Unit
) {
    Column {
        Text(
            "Name: ${characteristic.name}",
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 6.sp
        )
        Text(
            "Value: ${characteristic.value.contentToString()}",
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 6.sp
        )
        Row {
            Button(
                onClick = {
                    onEvent(UiEvent.OnReadCharacteristic(macId, characteristic))
                },
                modifier = Modifier
                    .width(140.dp)
                    .padding(start = 20.dp, top = 20.dp)
            ) {
                Text("Read")
            }
            Button(
                onClick = {
                    onEvent(UiEvent.OnWriteCharacteristic(macId, characteristic, "123"))
                },
                modifier = Modifier
                    .width(140.dp)
                    .padding(start = 20.dp, top = 20.dp)
            ) {
                Text("Write")
            }
        }
    }
}
