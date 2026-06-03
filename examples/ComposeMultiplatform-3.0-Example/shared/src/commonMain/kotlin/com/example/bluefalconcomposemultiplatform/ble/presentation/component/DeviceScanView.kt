package com.example.bluefalconcomposemultiplatform.ble.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluefalconcomposemultiplatform.ble.presentation.BluetoothDeviceState
import com.example.bluefalconcomposemultiplatform.ble.presentation.UiEvent
import dev.bluefalcon.core.BluetoothPeripheral

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScanView(
    state: BluetoothDeviceState,
    onEvent: (UiEvent) -> Unit
) {
    val commonServiceUuidSuggestions = remember(state.devices) {
        state.devices.values
            .asSequence()
            .flatMap { device ->
                device.peripheral.services.asSequence().map { it.uuid.toString() }
            }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .take(8)
            .toList()
    }
    val commonAdvertisementFilters = remember(state.devices) {
        state.devices.values
            .asSequence()
            .flatMap { device ->
                sequenceOf(device.peripheral.name.orEmpty().trim()) +
                    device.peripheral.services.asSequence().map { it.uuid.toString() }
            }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .take(8)
            .toList()
    }
    val filteredDevices = remember(state.devices, state.scanAdvertisementFilter) {
        state.devices.values
            .asSequence()
            .filter { device ->
                state.scanAdvertisementFilter.isBlank() || advertisementSearchText(device.peripheral).contains(
                    other = state.scanAdvertisementFilter,
                    ignoreCase = true
                )
            }
            .sortedByDescending { it.rssi ?: it.peripheral.rssi }
            .toList()
    }

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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                FilterInputWithSuggestions(
                    value = state.scanUuidFilter,
                    label = { Text("Service UUID filter (applied at scan level)") },
                    suggestions = commonServiceUuidSuggestions,
                    onValueChange = { onEvent(UiEvent.OnScanUuidFilterChanged(it)) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                FilterInputWithSuggestions(
                    value = state.scanAdvertisementFilter,
                    label = { Text("Advertisement data filter") },
                    suggestions = commonAdvertisementFilters,
                    onValueChange = { onEvent(UiEvent.OnScanAdvertisementFilterChanged(it)) }
                )
                if (state.scanUuidFilter.isNotBlank() || state.scanAdvertisementFilter.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            modifier = Modifier.semantics { contentDescription = "Clear all filters" },
                            onClick = {
                                onEvent(UiEvent.OnScanUuidFilterChanged(""))
                                onEvent(UiEvent.OnScanAdvertisementFilterChanged(""))
                            }
                        ) {
                            Text("Clear filters")
                        }
                    }
                }
            }

            if (filteredDevices.isEmpty()) {
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
                    if (state.isScanning) {
                       CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (state.devices.isEmpty()) {
                            if (state.isScanning) "Scanning for devices..." else "Press SCAN to find nearby devices"
                        } else {
                            "No devices match the current filters"
                        },
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 16.sp
                    )
                }
            } else {
                Text(
                    text = "DISCOVERED DEVICES (${filteredDevices.size}/${state.devices.size})",
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
                        filteredDevices
                    ) { device ->
                        @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
                        ScanResultCard(
                            deviceName = device.peripheral.name,
                            macId = device.peripheral.uuid,
                            rssi = device.rssi ?: device.peripheral.rssi,
                            serviceUuids = device.peripheral.services.map { it.uuid.toString() },
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

@Composable
private fun FilterInputWithSuggestions(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    suggestions: List<String>
) {
    var isDropdownExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                if (suggestions.isNotEmpty()) {
                    IconButton(onClick = { isDropdownExpanded = !isDropdownExpanded }) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Show common filters"
                        )
                    }
                }
            }
        )
        DropdownMenu(
            expanded = isDropdownExpanded,
            onDismissRequest = { isDropdownExpanded = false }
        ) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        onValueChange(suggestion)
                        isDropdownExpanded = false
                    }
                )
            }
        }
    }
}

private fun advertisementSearchText(peripheral: BluetoothPeripheral): String {
    val serviceText = peripheral.services.joinToString(separator = " ") { it.uuid.toString() }
    return "${peripheral.name.orEmpty()} $serviceText".trim()
}