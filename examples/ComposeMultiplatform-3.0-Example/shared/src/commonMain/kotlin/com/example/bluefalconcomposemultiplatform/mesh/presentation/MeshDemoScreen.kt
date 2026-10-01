package com.example.bluefalconcomposemultiplatform.mesh.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.bluefalconcomposemultiplatform.mesh.domain.MeshEnvelope
import dev.bluefalcon.plugins.mesh.MeshNodeState
import dev.bluefalcon.plugins.metrics.MetricsPlugin
import dev.bluefalcon.plugins.metrics.MetricsSnapshot

/**
 * Composable screen demonstrating the Mesh plugin as an internet-less group chat (ADR 0016).
 *
 * Flow:
 * - Enter a display name and join the mesh
 * - See a live, WhatsApp-style group chat whose messages and participant list are synced across
 *   every connected mesh node (no server, no internet)
 * - Leave to stop the mesh node
 */
@Composable
fun MeshDemoScreen(
    viewModel: MeshDemoViewModel,
    metricsPlugin: MetricsPlugin,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val metrics by metricsPlugin.snapshot.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        if (!state.joined) {
            JoinMeshView(
                displayNameInput = state.displayNameInput,
                onDisplayNameChange = { viewModel.onEvent(MeshDemoEvent.UpdateDisplayNameInput(it)) },
                onJoin = { viewModel.onEvent(MeshDemoEvent.JoinMesh) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Applied on the outer container (rather than just the input row) so
                    // that as the keyboard rises, it eats into the message list's weight(1f)
                    // space rather than being drawn underneath/behind the keyboard - the
                    // list shrinks and the input bar stays pinned directly above the
                    // keyboard, like a normal chat client.
                    .imePadding()
                    .padding(16.dp),
            ) {
                MeshStatusHeader(
                    nodeState = state.nodeState,
                    localDisplayName = state.localDisplayName,
                    neighborCount = state.neighborCount,
                    participants = state.participants,
                    onLeave = { viewModel.onEvent(MeshDemoEvent.StopMesh) },
                    onClear = { viewModel.onEvent(MeshDemoEvent.ClearMessages) },
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Live connection/latency/throughput metrics (blue-falcon-plugin-metrics, ADR 0012).
                // Every scan/connect/disconnect/read/write the mesh node performs against BlueFalcon
                // flows through this same plugin, so these counters update live as the mesh runs.
                // Collapsible so the user can hide it to give the chat list more room; it stays
                // pinned above the list (like the status header) rather than scrolling with it.
                MetricsSummaryCard(metrics = metrics)

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Chat (${state.messages.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (state.messages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No messages yet - send one to start chatting",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val listState = rememberLazyListState()

                    // Messages are newest-first and reverseLayout puts index 0 at the bottom, so
                    // this keeps the latest message in view as new ones arrive - matching how a
                    // chat app behaves - while the user can still freely scroll up through history.
                    LaunchedEffect(state.messages.firstOrNull()?.id) {
                        if (state.messages.isNotEmpty()) {
                            listState.animateScrollToItem(0)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        reverseLayout = true,
                    ) {
                        itemsIndexed(state.messages, key = { _, item -> item.id }) { index, message ->
                            // In reverseLayout, the item "below" in reading order (same sender,
                            // consecutive) is at index - 1, not index + 1.
                            val previous = state.messages.getOrNull(index - 1)
                            val isGroupedWithPrevious = previous != null && previous.userId == message.userId
                            ChatMessageBubble(
                                message = message,
                                showSenderName = !message.isOwnMessage && !isGroupedWithPrevious,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                MessageInput(
                    text = state.messageToSend,
                    onTextChange = { viewModel.onEvent(MeshDemoEvent.UpdateMessageText(it)) },
                    onSend = { viewModel.onEvent(MeshDemoEvent.SendMessage) },
                )
            }
        }

        // Error snackbar
        state.error?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = { viewModel.onEvent(MeshDemoEvent.DismissError) }) {
                        Text("Dismiss")
                    }
                },
            ) {
                Text(error)
            }
        }
    }
}

@Composable
private fun JoinMeshView(
    displayNameInput: String,
    onDisplayNameChange: (String) -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Hub,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Join the Mesh Chat",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Enter a display name to connect to nearby devices and start chatting - " +
                "no internet connection required.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = displayNameInput,
            onValueChange = onDisplayNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Display name") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onJoin,
            modifier = Modifier.fillMaxWidth(),
            enabled = displayNameInput.isNotBlank(),
        ) {
            Text("Join Mesh")
        }
    }
}

@Composable
private fun MeshStatusHeader(
    nodeState: MeshNodeState,
    localDisplayName: String,
    neighborCount: Int,
    participants: List<MeshEnvelope.UserJoined>,
    onLeave: () -> Unit,
    onClear: () -> Unit,
) {
    var participantsExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (nodeState) {
                MeshNodeState.Running -> MaterialTheme.colorScheme.primaryContainer
                MeshNodeState.Stopping -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        // Floats visually above the scrolling chat list beneath it (ADR 0016).
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            when (nodeState) {
                                MeshNodeState.Running -> MaterialTheme.colorScheme.primary
                                MeshNodeState.Stopping -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.outline
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Hub,
                        contentDescription = "Mesh status",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = localDisplayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when (nodeState) {
                            MeshNodeState.Idle -> "Not connected"
                            MeshNodeState.Running -> "Connected - $neighborCount device(s) nearby"
                            MeshNodeState.Stopping -> "Leaving..."
                            MeshNodeState.Stopped -> "Disconnected"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(onClick = onLeave, enabled = nodeState != MeshNodeState.Stopping) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Leave mesh")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { participantsExpanded = !participantsExpanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Groups,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (participants.size == 1) {
                        "1 known participant"
                    } else {
                        "${participants.size} known participants"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (participantsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (participantsExpanded) "Hide participants" else "Show participants",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear")
                }
            }

            if (participantsExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                if (participants.isEmpty()) {
                    Text(
                        text = "No participants known yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        participants.sortedBy { it.displayName }.forEach { participant ->
                            Text(
                                text = "• ${participant.displayName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricsSummaryCard(metrics: MetricsSnapshot) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        // Floats visually above the scrolling chat list beneath it (ADR 0016).
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Metrics",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide metrics" else "Show metrics",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MetricStat(
                        label = "Connects",
                        value = "${metrics.connectSuccessCount}/${metrics.connectSuccessCount + metrics.connectFailureCount}",
                    )
                    MetricStat(
                        label = "Reads",
                        value = "${metrics.readSuccessCount}/${metrics.readSuccessCount + metrics.readFailureCount}",
                    )
                    MetricStat(
                        label = "Writes",
                        value = "${metrics.writeSuccessCount}/${metrics.writeSuccessCount + metrics.writeFailureCount}",
                    )
                    MetricStat(label = "Bytes ↓", value = metrics.bytesRead.toString())
                    MetricStat(label = "Bytes ↑", value = metrics.bytesWritten.toString())
                }
            }
        }
    }
}

@Composable
private fun MetricStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
        )

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = onSend,
            enabled = text.isNotBlank(),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
        }
    }
}

@Composable
private fun ChatMessageBubble(message: ChatMessage, showSenderName: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOwnMessage) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.8f),
            shape = RoundedCornerShape(12.dp),
            color = if (message.isOwnMessage) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (showSenderName) {
                    Text(
                        text = message.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isOwnMessage) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.isOwnMessage) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

/**
 * Formats epoch millis as a UTC `HH:mm` clock time. Using UTC (rather than each platform's local
 * time zone) keeps timestamps directly comparable across mesh nodes without a fifth
 * platform-specific time-zone actual for this demo.
 */
private fun formatTimestamp(epochMillis: Long): String {
    val totalSeconds = epochMillis / 1000
    val hours = (totalSeconds / 3600) % 24
    val minutes = (totalSeconds / 60) % 60
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}
