package com.example.bluefalconcomposemultiplatform.ble.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ConnectWithoutContact
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluefalconcomposemultiplatform.ble.presentation.UiEvent
import com.example.bluefalconcomposemultiplatform.core.presentation.IconButton
import dev.bluefalcon.AdvertisementDataRetrievalKeys
import dev.bluefalcon.BTCharacteristic
import dev.bluefalcon.BTService
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothService

@Composable
fun FoundDeviceCard(
    deviceName: String?,
    macId: String,
    advertisementInfo: Map<AdvertisementDataRetrievalKeys, String>,
    rssi: Float?,
    services: List<BTService>,
    connected: Boolean,
    showFullDetails: Boolean,
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "NAME: $deviceName",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 12.sp
                    )
                    Row {
                        if (!connected) {
                            IconButton(
                                icon = Icons.Default.ConnectWithoutContact,
                                contentDescription = "Connect",
                                onClick = {
                                    onEvent(UiEvent.OnConnectClick(macId))
                                }
                            )
                        } else {
                            IconButton(
                                icon = Icons.Default.LinkOff,
                                contentDescription = "Disconnect",
                                onClick = {
                                    onEvent(UiEvent.OnDisconnectClick(macId))
                                }
                            )
                        }
                        if (connected) {
                            if (showFullDetails) {
                                IconButton(
                                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Close",
                                    onClick = {
                                        onEvent.invoke(UiEvent.OnShowDetailsClick(macId))
                                    }
                                )
                            } else {
                                IconButton(
                                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Open",
                                    onClick = {
                                        onEvent.invoke(UiEvent.OnShowDetailsClick(macId))
                                    }
                                )
                            }
                        }
                    }
                }

                Row {
                    advertisementInfo.forEach { (key, value) ->
                        Text(
                            text = "${key.name}:${value}",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 10.sp
                        )
                    }
                }

                rssi?.let {
                    Text(
                        text = "RSSI: $rssi",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 10.sp
                    )
                }

                if (showFullDetails) {
                    Row {
                        Button({
                            onEvent(UiEvent.OnExportDetailsClick(macId))
                        }) {
                            Text("Export Services & Characteristics")
                        }
                    }

                    Column {
                        Text(
                            "Services",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 10.sp
                        )
                        services.forEach {
                            ServiceRow(macId, it, onEvent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ServiceRow(
    macId: String,
    service: BTService,
    onEvent: (UiEvent) -> Unit
) {
    Column {
        Text(
            "Name: ${service.name}",
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 10.sp
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
    characteristic: BTCharacteristic,
    onEvent: (UiEvent) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row {
            Column {
                Text(
                    "Name: ${characteristic.name}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 10.sp
                )
                Text(
                    "Value: ${characteristic.value.contentToString()}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 10.sp
                )
                Text(
                    "DValue: ${characteristic.value?.decodeToString()}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 10.sp
                )
            }
        }
        Row {
            IconButton(
                icon = Icons.Default.ArrowDownward,
                contentDescription = "Read",
                onClick = {
                    onEvent(UiEvent.OnReadCharacteristic(macId, characteristic))
                }
            )
            IconButton(
                icon = Icons.Default.ArrowUpward,
                contentDescription = "Write",
                onClick = {
                    onEvent(UiEvent.OnWriteCharacteristic(macId, characteristic, "123"))
                }
            )
        }
    }
}
