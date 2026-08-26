package com.example.bluefalconcomposemultiplatform.peripheral.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.bluefalcon.peripheral.PeripheralManagerState

@Composable
fun PeripheralServerView(
    viewModel: PeripheralServerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    PeripheralServerView(
        state = state,
        onSelectProfile = viewModel::selectProfile,
        onStart = viewModel::start,
        onStop = viewModel::stop,
        onPayloadChange = viewModel::setPayloadText,
        onSend = viewModel::sendNotification,
        onToggleHeartRateSimulation = viewModel::toggleHeartRateSimulation,
        onSetBondingRequired = viewModel::setBondingRequired,
        onSetBondOnHeartRateRead = viewModel::setBondOnHeartRateRead,
        modifier = modifier,
    )
}

@Composable
fun PeripheralServerView(
    state: PeripheralServerState,
    onSelectProfile: (PeripheralProfile) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPayloadChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleHeartRateSimulation: () -> Unit,
    onSetBondingRequired: (Boolean) -> Unit,
    onSetBondOnHeartRateRead: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.supported) {
            item {
                UnsupportedPeripheralCard()
            }
        } else {
            item {
                ProfileSelectorRow(
                    selected = state.profile,
                    enabled = state.canSwitchProfile,
                    onSelectProfile = onSelectProfile,
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = state.profile.displayTitle(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "State: ${state.managerState.displayLabel()}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Connections: ${state.sessionCount} · " +
                                "Subscribed: ${state.subscribedSessionCount}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.profile == PeripheralProfile.HEART_RATE_MONITOR) {
                            Text(
                                text = "Bonded sessions: ${state.bondedSessionCount}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onStart,
                        enabled = state.canStart,
                    ) {
                        Text("Start")
                    }
                    OutlinedButton(
                        onClick = onStop,
                        enabled = state.canStop,
                    ) {
                        Text("Stop")
                    }
                }
            }

            when (state.profile) {
                PeripheralProfile.ECHO -> {
                    item {
                        OutlinedTextField(
                            value = state.payloadText,
                            onValueChange = onPayloadChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Notification payload") },
                            supportingText = {
                                Text(
                                    "Sent to sessions subscribed to the echo " +
                                        "characteristic",
                                )
                            },
                            singleLine = true,
                        )
                    }

                    item {
                        Button(
                            onClick = onSend,
                            enabled = state.canSend,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Send notification")
                        }
                    }
                }

                PeripheralProfile.HEART_RATE_MONITOR -> {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Heart rate: ${state.heartRateBpm} bpm",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = "Require bonding to read " +
                                            "Body Sensor Location",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = state.bondingRequired,
                                        onCheckedChange = onSetBondingRequired,
                                        enabled = state.canToggleBondingRequirement,
                                    )
                                }
                                Text(
                                    text = if (state.bondingRequired) {
                                        "Reading Body Sensor Location requires " +
                                            "bonding: the first read is rejected " +
                                            "and triggers a pairing request."
                                    } else {
                                        "Bonding is not enforced: Body Sensor " +
                                            "Location can be read immediately."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = "Request bonding as soon as " +
                                            "Heart Rate Measurement is read",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = state.bondOnHeartRateRead,
                                        onCheckedChange = onSetBondOnHeartRateRead,
                                        enabled = state.canToggleBondOnHeartRateRead,
                                    )
                                }
                                Text(
                                    text = if (state.bondOnHeartRateRead) {
                                        "Reading Heart Rate Measurement requests " +
                                            "bonding immediately: the first read is " +
                                            "rejected and triggers a pairing request."
                                    } else {
                                        "Heart Rate Measurement stays notify-only " +
                                            "and rejects explicit reads."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    item {
                        Button(
                            onClick = onToggleHeartRateSimulation,
                            enabled = state.canToggleHeartRateSimulation,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (state.simulatingHeartRate) {
                                    "Stop heart rate simulation"
                                } else {
                                    "Start heart rate simulation"
                                },
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Activity log",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (state.log.isEmpty()) {
                item {
                    Text(
                        text = "No activity yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(state.log.asReversed()) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = entry,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSelectorRow(
    selected: PeripheralProfile,
    enabled: Boolean,
    onSelectProfile: (PeripheralProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PeripheralProfile.entries.forEach { profile ->
            val isSelected = profile == selected
            if (isSelected) {
                Button(onClick = { onSelectProfile(profile) }, enabled = enabled) {
                    Text(profile.displayTitle())
                }
            } else {
                OutlinedButton(onClick = { onSelectProfile(profile) }, enabled = enabled) {
                    Text(profile.displayTitle())
                }
            }
        }
    }
}

@Composable
private fun UnsupportedPeripheralCard(
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Peripheral mode is unavailable",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "The JVM desktop target does not expose a GATT server backend. " +
                    "Run this example on Android, iOS, or native macOS.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun PeripheralManagerState.displayLabel(): String = when (this) {
    PeripheralManagerState.Stopped -> "Stopped"
    PeripheralManagerState.Starting -> "Starting"
    PeripheralManagerState.Running -> "Running"
    PeripheralManagerState.Stopping -> "Stopping"
    is PeripheralManagerState.Failed ->
        "Failed: ${cause.message ?: cause::class.simpleName ?: "unknown error"}"
    PeripheralManagerState.Closed -> "Closed"
}

private fun PeripheralProfile.displayTitle(): String = when (this) {
    PeripheralProfile.ECHO -> "Echo"
    PeripheralProfile.HEART_RATE_MONITOR -> "Heart Rate Monitor"
}
