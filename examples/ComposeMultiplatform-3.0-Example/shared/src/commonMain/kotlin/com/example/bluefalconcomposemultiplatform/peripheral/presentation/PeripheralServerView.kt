package com.example.bluefalconcomposemultiplatform.peripheral.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
        onStart = viewModel::start,
        onStop = viewModel::stop,
        onPayloadChange = viewModel::setPayloadText,
        onSend = viewModel::sendNotification,
        modifier = modifier,
    )
}

@Composable
fun PeripheralServerView(
    state: PeripheralServerState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPayloadChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.supported) {
        UnsupportedPeripheralCard(modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
                    text = "Echo GATT server",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "State: ${state.managerState.displayLabel()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Sessions: ${state.sessionCount} · " +
                        "Subscribed: ${state.subscribedSessionCount}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

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

        OutlinedTextField(
            value = state.payloadText,
            onValueChange = onPayloadChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Notification payload") },
            supportingText = {
                Text("Sent to sessions subscribed to the echo characteristic")
            },
        )

        Button(
            onClick = onSend,
            enabled = state.canSend,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Send notification")
        }

        Text(
            text = "Activity log",
            style = MaterialTheme.typography.titleMedium,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
private fun UnsupportedPeripheralCard(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
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
