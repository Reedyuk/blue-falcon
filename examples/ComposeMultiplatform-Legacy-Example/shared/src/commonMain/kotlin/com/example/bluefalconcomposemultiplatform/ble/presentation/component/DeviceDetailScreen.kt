package com.example.bluefalconcomposemultiplatform.ble.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluefalconcomposemultiplatform.ble.presentation.EnhancedBluetoothPeripheral
import com.example.bluefalconcomposemultiplatform.ble.presentation.UiEvent
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothService

@OptIn(ExperimentalMaterial3Api::class, kotlin.uuid.ExperimentalUuidApi::class)
@Composable
fun DeviceDetailScreen(
    device: EnhancedBluetoothPeripheral,
    onEvent: (UiEvent) -> Unit
) {
    val macId = device.peripheral.uuid
    var showMtuDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = device.peripheral.name ?: "Unknown Device",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(
                            text = macId,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { onEvent(UiEvent.OnNavigateBack) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                actions = {
                    OutlinedButton(
                        onClick = { onEvent(UiEvent.OnDisconnectClick(macId)) },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            "DISCONNECT",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 12.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { paddingValues ->
        val services = device.peripheral.services.values.toList()

        if (services.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = "Connected",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Discovering services...",
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 16.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                item {
                    DeviceInfoCard(device = device, onRequestMtu = { showMtuDialog = true })
                }
                item {
                    Text(
                        text = "SERVICES",
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                }
                items(services) { service ->
                    ServiceCard(
                        macId = macId,
                        service = service,
                        onEvent = onEvent
                    )
                }
            }
        }
    }

    if (showMtuDialog) {
        MtuDialog(
            onDismiss = { showMtuDialog = false },
            onRequest = { mtuSize ->
                onEvent(UiEvent.OnChangeMtu(macId, mtuSize))
                showMtuDialog = false
            }
        )
    }
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@Composable
fun DeviceInfoCard(
    device: EnhancedBluetoothPeripheral,
    onRequestMtu: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "DEVICE INFO",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "RSSI",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = device.peripheral.rssi?.let { "${it.toInt()} dBm" } ?: "—",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column {
                    Text(
                        text = "MTU",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = device.peripheral.mtuSize?.toString() ?: "—",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column {
                    Text(
                        text = "SERVICES",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${device.peripheral.services.size}",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            device.mtuStatus?.let { status ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = status,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onRequestMtu
            ) {
                Text("REQUEST MTU", fontSize = 12.sp)
            }
        }
    }
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@Composable
fun ServiceCard(
    macId: String,
    service: BluetoothService,
    onEvent: (UiEvent) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            // Service header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = service.name ?: "Unknown Service",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "UUID: ${service.uuid}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Characteristics (expandable)
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    service.characteristics.forEach { characteristic ->
                        CharacteristicItem(
                            macId = macId,
                            characteristic = characteristic,
                            onEvent = onEvent
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@Composable
fun CharacteristicItem(
    macId: String,
    characteristic: BluetoothCharacteristic,
    onEvent: (UiEvent) -> Unit
) {
    var showWriteDialog by remember { mutableStateOf(false) }
    var showDescriptors by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Characteristic name and UUID
        Text(
            text = characteristic.name ?: "Unknown Characteristic",
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "UUID: ${characteristic.uuid}",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Value display
        val valueText = characteristic.value?.let { bytes ->
            if (bytes.isEmpty()) "(empty)"
            else {
                val hex = bytes.joinToString(" ") { byte ->
                    (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
                }
                val utf8 = runCatching { bytes.decodeToString() }.getOrDefault("(invalid UTF-8)")
                "Value: (0x) $hex | \"$utf8\""
            }
        } ?: "Value: —"

        Text(
            text = valueText,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Action buttons row
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Read button
            IconButton(
                onClick = { onEvent(UiEvent.OnReadCharacteristic(macId, characteristic)) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = "Read",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Write button
            IconButton(
                onClick = { showWriteDialog = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Write",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Notify toggle button
            IconButton(
                onClick = { onEvent(UiEvent.OnToggleNotify(macId, characteristic)) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (characteristic.isNotifying)
                        Icons.Default.Notifications
                    else
                        Icons.Default.NotificationsOff,
                    contentDescription = if (characteristic.isNotifying)
                        "Disable notifications"
                    else
                        "Enable notifications",
                    tint = if (characteristic.isNotifying)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Properties labels
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = "READ",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    text = "WRITE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    text = if (characteristic.isNotifying) "NOTIFY ON" else "NOTIFY",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (characteristic.isNotifying)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.outline
                )
            }
        }

        // Descriptors section
        if (characteristic.descriptors.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (showDescriptors) "▾ Descriptors (${characteristic.descriptors.size})"
                       else "▸ Descriptors (${characteristic.descriptors.size})",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { showDescriptors = !showDescriptors }
                    .padding(vertical = 4.dp)
            )
            AnimatedVisibility(visible = showDescriptors) {
                Column(
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    characteristic.descriptors.forEach { descriptor ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Descriptor: $descriptor",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    onEvent(UiEvent.OnReadDescriptor(macId, characteristic, descriptor))
                                }
                            ) {
                                Text("READ", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // Write dialog
    if (showWriteDialog) {
        WriteCharacteristicDialog(
            onDismiss = { showWriteDialog = false },
            onWrite = { value ->
                onEvent(UiEvent.OnWriteCharacteristic(macId, characteristic, value))
                showWriteDialog = false
            }
        )
    }
}

@Composable
fun WriteCharacteristicDialog(
    onDismiss: () -> Unit,
    onWrite: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Write value",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Enter value to write (UTF-8 text):",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Enter value...") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onWrite(text) },
                enabled = text.isNotEmpty()
            ) {
                Text("WRITE")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        }
    )
}

@Composable
fun MtuDialog(
    onDismiss: () -> Unit,
    onRequest: (Int) -> Unit
) {
    var mtuText by remember { mutableStateOf("512") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Request MTU",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Enter desired MTU size (23-517):",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = mtuText,
                    onValueChange = { mtuText = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("512") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { mtuText.toIntOrNull()?.let { onRequest(it) } },
                enabled = mtuText.isNotEmpty() && mtuText.toIntOrNull() != null
            ) {
                Text("REQUEST")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        }
    )
}
