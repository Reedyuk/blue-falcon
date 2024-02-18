package com.example.bluefalconcomposemultiplatform.ble.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

@Composable
fun FoundDeviceCard(
    deviceName: String?,
    macId: String,
    rssi: Float?,
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