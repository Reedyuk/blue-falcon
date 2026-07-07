package com.example.bluefalconcomposemultiplatform.ble.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SignalCellularAlt2Bar
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluefalconcomposemultiplatform.ble.util.BleDeviceType
import com.example.bluefalconcomposemultiplatform.ble.util.rssiToProximityLabel

@Composable
fun ScanResultCard(
    deviceName: String?,
    macId: String,
    rssi: Float?,
    serviceUuids: List<String> = emptyList(),
    manufacturerData: Map<Int, String> = emptyMap(),
    connected: Boolean,
    onConnect: () -> Unit,
    onSelect: () -> Unit
) {
    val deviceType = BleDeviceType.detect(deviceName, serviceUuids)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(enabled = connected) { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (connected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Device type icon
            Icon(
                imageVector = deviceType.toIcon(),
                contentDescription = deviceType.toLabel(),
                modifier = Modifier.size(36.dp),
                tint = if (connected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Device info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (!deviceName.isNullOrBlank()) deviceName else "Unknown Device",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (!deviceName.isNullOrBlank())
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = macId,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = deviceType.toLabel(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium
                )
                if (manufacturerData.isNotEmpty()) {
                    val mfText = manufacturerData.entries.joinToString(", ") { (id, hex) ->
                        "0x${id.toString(16).padStart(4, '0').uppercase()}: $hex"
                    }
                    Text(
                        text = "MFR: $mfText",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Signal strength + proximity column
            rssi?.let {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val (signalIcon, signalColor) = when {
                        it > -50 -> Icons.Default.SignalCellular4Bar to MaterialTheme.colorScheme.primary
                        it > -65 -> Icons.Default.SignalCellularAlt to MaterialTheme.colorScheme.tertiary
                        it > -75 -> Icons.Default.SignalCellularAlt2Bar to MaterialTheme.colorScheme.secondary
                        else     -> Icons.Default.SignalCellularAlt1Bar to MaterialTheme.colorScheme.error
                    }
                    Icon(
                        imageVector = signalIcon,
                        contentDescription = "Signal strength",
                        modifier = Modifier.size(22.dp),
                        tint = signalColor
                    )
                    Text(
                        text = rssiToProximityLabel(it),
                        fontSize = 9.sp,
                        color = signalColor,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${it.toInt()} dBm",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Connect / Open button
            if (connected) {
                Button(
                    onClick = onSelect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("OPEN", fontSize = 12.sp)
                }
            } else {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("CONNECT", fontSize = 12.sp)
                }
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

private fun BleDeviceType.toIcon(): ImageVector = when (this) {
    BleDeviceType.WATCH              -> Icons.Filled.Watch
    BleDeviceType.HEADPHONES         -> Icons.Filled.Headphones
    BleDeviceType.HEART_RATE_MONITOR -> Icons.Filled.MonitorHeart
    BleDeviceType.SPEAKER            -> Icons.Filled.Speaker
    BleDeviceType.THERMOMETER        -> Icons.Filled.Thermostat
    BleDeviceType.WEIGHT_SCALE       -> Icons.Filled.Scale
    BleDeviceType.CYCLING            -> Icons.Filled.PedalBike
    BleDeviceType.RUNNING            -> Icons.Filled.DirectionsRun
    BleDeviceType.HID                -> Icons.Filled.Keyboard
    BleDeviceType.PHONE              -> Icons.Filled.Phone
    BleDeviceType.UNKNOWN            -> Icons.Filled.Bluetooth
}

private fun BleDeviceType.toLabel(): String = when (this) {
    BleDeviceType.WATCH              -> "Watch / Band"
    BleDeviceType.HEADPHONES         -> "Headphones"
    BleDeviceType.HEART_RATE_MONITOR -> "Heart Rate Monitor"
    BleDeviceType.SPEAKER            -> "Speaker"
    BleDeviceType.THERMOMETER        -> "Thermometer"
    BleDeviceType.WEIGHT_SCALE       -> "Weight Scale"
    BleDeviceType.CYCLING            -> "Cycling Sensor"
    BleDeviceType.RUNNING            -> "Running Sensor"
    BleDeviceType.HID                -> "HID Device"
    BleDeviceType.PHONE              -> "Phone"
    BleDeviceType.UNKNOWN            -> "BLE Device"
}

