package com.example.bluefalconcomposemultiplatform.mesh.presentation

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.plugins.mesh.MeshConfig
import dev.bluefalcon.plugins.mesh.MeshMessage
import dev.bluefalcon.plugins.mesh.MeshNode
import dev.bluefalcon.plugins.mesh.MeshNodeState
import dev.icerock.moko.mvvm.viewmodel.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the mesh demo screen.
 */
data class MeshDemoState(
    val nodeState: MeshNodeState = MeshNodeState.Idle,
    val nodeUuid: String = "",
    val messages: List<ReceivedMessage> = emptyList(),
    val neighborCount: Int = 0,
    val messageToSend: String = "",
    val error: String? = null,
)

/**
 * Represents a message received from the mesh.
 */
data class ReceivedMessage(
    val id: String,
    val originUuid: String,
    val hopCount: Int,
    val payload: String,
    val receivedAt: Long = currentTimeMillis(),
)

internal expect fun currentTimeMillis(): Long

/**
 * ViewModel for the mesh demo screen.
 *
 * Demonstrates the basic usage of MeshNode:
 * - Starting/stopping the mesh node
 * - Broadcasting messages
 * - Receiving messages from the mesh network
 */
class MeshDemoViewModel(
    private val blueFalcon: BlueFalcon,
    private val peripheral: BlueFalconPeripheral?,
) : ViewModel() {

    private val _state = MutableStateFlow(MeshDemoState())
    val state: StateFlow<MeshDemoState> = _state.asStateFlow()

    private var meshNode: MeshNode? = null

    init {
        // If no peripheral is available, show error
        if (peripheral == null) {
            _state.update { it.copy(error = "Peripheral role not available on this platform") }
        }
    }

    fun onEvent(event: MeshDemoEvent) {
        when (event) {
            is MeshDemoEvent.StartMesh -> startMesh()
            is MeshDemoEvent.StopMesh -> stopMesh()
            is MeshDemoEvent.SendMessage -> sendMessage()
            is MeshDemoEvent.UpdateMessageText -> updateMessageText(event.text)
            is MeshDemoEvent.ClearMessages -> clearMessages()
            is MeshDemoEvent.DismissError -> dismissError()
        }
    }

    private fun startMesh() {
        val periph = peripheral ?: run {
            _state.update { it.copy(error = "Peripheral role not available") }
            return
        }

        viewModelScope.launch {
            try {
                val node = MeshNode(
                    central = blueFalcon,
                    peripheral = periph,
                    config = MeshConfig().apply {
                        maxHopCount = 5
                        advertisedName = "BlueFalcon Mesh Demo"
                        autoConnectToNeighbors = true
                        maxNeighborConnections = 4
                    },
                )
                meshNode = node

                _state.update {
                    it.copy(
                        nodeUuid = node.nodeUuid,
                        error = null,
                    )
                }

                // Observe mesh state
                launch {
                    node.state.collect { meshState ->
                        _state.update { it.copy(nodeState = meshState) }
                    }
                }

                // Observe inbound messages
                launch {
                    node.inboundMessages.collect { message ->
                        handleInboundMessage(message)
                    }
                }

                // Start the mesh
                node.start()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to start mesh: ${e.message}") }
            }
        }
    }

    private fun stopMesh() {
        viewModelScope.launch {
            try {
                meshNode?.stop()
                meshNode = null
                _state.update { it.copy(nodeState = MeshNodeState.Idle) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to stop mesh: ${e.message}") }
            }
        }
    }

    private fun sendMessage() {
        val text = _state.value.messageToSend.trim()
        if (text.isEmpty()) return

        viewModelScope.launch {
            try {
                meshNode?.broadcast(text.encodeToByteArray())
                _state.update { it.copy(messageToSend = "") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to send message: ${e.message}") }
            }
        }
    }

    private fun updateMessageText(text: String) {
        _state.update { it.copy(messageToSend = text) }
    }

    private fun handleInboundMessage(message: MeshMessage) {
        val received = ReceivedMessage(
            id = message.id.value,
            originUuid = message.originUuid,
            hopCount = message.hopCount,
            payload = message.payload.decodeToString(),
        )

        _state.update { current ->
            // Keep last 50 messages
            val messages = (listOf(received) + current.messages).take(50)
            current.copy(messages = messages)
        }
    }

    private fun clearMessages() {
        _state.update { it.copy(messages = emptyList()) }
    }

    private fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        // Stop mesh when ViewModel is cleared
        meshNode?.let { node ->
            viewModelScope.launch {
                runCatching { node.stop() }
            }
        }
    }
}

/**
 * Events from the mesh demo UI.
 */
sealed interface MeshDemoEvent {
    data object StartMesh : MeshDemoEvent
    data object StopMesh : MeshDemoEvent
    data object SendMessage : MeshDemoEvent
    data class UpdateMessageText(val text: String) : MeshDemoEvent
    data object ClearMessages : MeshDemoEvent
    data object DismissError : MeshDemoEvent
}
