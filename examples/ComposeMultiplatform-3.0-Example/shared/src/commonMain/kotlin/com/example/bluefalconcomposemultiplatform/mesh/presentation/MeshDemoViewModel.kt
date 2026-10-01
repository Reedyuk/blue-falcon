package com.example.bluefalconcomposemultiplatform.mesh.presentation

import com.example.bluefalconcomposemultiplatform.mesh.domain.MeshEnvelope
import com.example.bluefalconcomposemultiplatform.mesh.domain.MeshLedger
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.plugins.mesh.MeshConfig
import dev.bluefalcon.plugins.mesh.MeshNode
import dev.bluefalcon.plugins.mesh.MeshNodeState
import dev.icerock.moko.mvvm.viewmodel.ViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * UI state for the mesh demo screen: an internet-less, ledger-synced group chat over
 * `blue-falcon-plugin-mesh` (ADR 0016).
 */
data class MeshDemoState(
    val nodeState: MeshNodeState = MeshNodeState.Idle,
    val nodeUuid: String = "",
    val neighborCount: Int = 0,
    /** The name the user is currently typing, before joining. */
    val displayNameInput: String = "",
    /** `true` once the user has joined the mesh under a chosen display name. */
    val joined: Boolean = false,
    val localUserId: String = "",
    val localDisplayName: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val participants: List<MeshEnvelope.UserJoined> = emptyList(),
    val messageToSend: String = "",
    val error: String? = null,
)

/**
 * A message shown in the mesh chat, whether it originated from this node (sent) or was received/
 * synced from another node in the mesh ledger.
 */
data class ChatMessage(
    val id: String,
    val userId: String,
    val displayName: String,
    val timestamp: Long,
    val text: String,
    val isOwnMessage: Boolean,
)

internal expect fun currentTimeMillis(): Long

/**
 * ViewModel for the mesh demo screen.
 *
 * Demonstrates building a minimal group chat on top of `MeshNode` (ADR 0011):
 * - Joining the mesh under a user-chosen display name
 * - Broadcasting timestamped chat messages, encoded as [MeshEnvelope]s
 * - Maintaining a [MeshLedger] of known users and messages, merged from locally sent messages,
 *   inbound mesh traffic, and periodic ledger-sync snapshots (ADR 0016)
 */
@OptIn(ExperimentalUuidApi::class)
class MeshDemoViewModel(
    private val blueFalcon: BlueFalcon,
    private val peripheral: BlueFalconPeripheral?,
) : ViewModel() {

    private val _state = MutableStateFlow(MeshDemoState())
    val state: StateFlow<MeshDemoState> = _state.asStateFlow()

    private var meshNode: MeshNode? = null
    private var ledger = MeshLedger()
    private var lastNeighborCount = 0

    init {
        // If no peripheral is available, show error
        if (peripheral == null) {
            _state.update { it.copy(error = "Peripheral role not available on this platform") }
        }
    }

    fun onEvent(event: MeshDemoEvent) {
        when (event) {
            is MeshDemoEvent.UpdateDisplayNameInput -> updateDisplayNameInput(event.text)
            is MeshDemoEvent.JoinMesh -> joinMesh()
            is MeshDemoEvent.StopMesh -> stopMesh()
            is MeshDemoEvent.SendMessage -> sendMessage()
            is MeshDemoEvent.UpdateMessageText -> updateMessageText(event.text)
            is MeshDemoEvent.ClearMessages -> clearMessages()
            is MeshDemoEvent.DismissError -> dismissError()
        }
    }

    private fun updateDisplayNameInput(text: String) {
        _state.update { it.copy(displayNameInput = text) }
    }

    private fun joinMesh() {
        val periph = peripheral ?: run {
            _state.update { it.copy(error = "Peripheral role not available") }
            return
        }
        val displayName = _state.value.displayNameInput.trim()
        if (displayName.isEmpty()) {
            _state.update { it.copy(error = "Enter a display name to join the mesh") }
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
                    logger = dev.bluefalcon.core.PrintLnLogger,
                )
                meshNode = node
                lastNeighborCount = 0

                val userId = node.nodeUuid
                _state.update {
                    it.copy(
                        nodeUuid = node.nodeUuid,
                        localUserId = userId,
                        localDisplayName = displayName,
                        joined = true,
                        error = null,
                    )
                }

                // Observe mesh state
                launch {
                    node.state.collect { meshState ->
                        _state.update { it.copy(nodeState = meshState) }
                    }
                }

                // Observe neighbor count (devices currently connected to the mesh). A rising
                // count means a new neighbor just connected - rebroadcast our ledger snapshot so
                // the newcomer (and anything beyond it, via flood relay) catches up on
                // participants/history, and also *ask* that neighbor to send its own snapshot
                // back (ADR 0016). The request is a second, independent delivery path: it
                // doesn't depend on the other side's own push (edge-trigger/heartbeat) landing -
                // only on this request reaching them, which prompts an immediate reply.
                launch {
                    node.neighborCount.collect { count ->
                        _state.update { it.copy(neighborCount = count) }
                        if (count > lastNeighborCount) {
                            syncLedgerToNeighbors()
                            requestLedgerFromNeighbors()
                            // The link can still be mid-MTU-negotiation/service-discovery the
                            // instant neighborCount first ticks up, so this first attempt may be
                            // silently skipped per-neighbor (see relayToCentralNeighbor/
                            // relayToPeripheralSession's readiness checks in MeshNode). Follow up
                            // shortly after to catch the link once it has actually settled,
                            // rather than waiting out the full heartbeat interval below.
                            launch {
                                delay(FOLLOW_UP_SYNC_DELAY_MS)
                                syncLedgerToNeighbors()
                                requestLedgerFromNeighbors()
                            }
                        }
                        lastNeighborCount = count
                    }
                }

                // Observe inbound messages
                launch {
                    node.inboundMessages.collect { message ->
                        val envelope = MeshEnvelope.decodeOrNull(message.payload) ?: return@collect
                        if (envelope is MeshEnvelope.LedgerRequest) {
                            // Someone is asking for our ledger (e.g. a neighbor that just
                            // (re)joined) - reply immediately rather than waiting for our own
                            // heartbeat, regardless of why their own push-based triggers may not
                            // have reached us.
                            if (envelope.requesterId != _state.value.localUserId) {
                                syncLedgerToNeighbors()
                            }
                            return@collect
                        }
                        mergeEnvelope(envelope)
                    }
                }

                // Self-healing fallback: periodically rebroadcast our ledger snapshot to
                // whatever neighbors are currently connected, independent of the
                // neighbor-count-increase edge above. That edge can be missed - e.g. if a
                // peer's connection drops and the underlying engine is slow (or fails) to
                // settle this node's own neighbor bookkeeping back down before the peer
                // reconnects, the later "increase" may never register as a transition. A
                // periodic resync bounds how long a reconnecting peer can go without
                // catching up on history/participants to one heartbeat interval (ADR 0016).
                launch {
                    while (isActive) {
                        delay(LEDGER_SYNC_HEARTBEAT_INTERVAL_MS)
                        if (node.neighborCount.value > 0) {
                            syncLedgerToNeighbors()
                        }
                    }
                }

                // Start the mesh, then announce ourselves to anyone already listening and ask
                // whoever's out there to reply with their own ledger (covers the case where we
                // are the one rejoining and need to catch up on history/participants - ADR 0016).
                node.start()
                val announcement = MeshEnvelope.UserJoined(
                    userId = userId,
                    displayName = displayName,
                    timestamp = currentTimeMillis(),
                )
                mergeEnvelope(announcement)
                node.broadcast(MeshEnvelope.encode(announcement))
                requestLedgerFromNeighbors()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to join mesh: ${e.message}") }
            }
        }
    }

    private fun stopMesh() {
        viewModelScope.launch {
            try {
                meshNode?.stop()
                meshNode = null
                lastNeighborCount = 0
                _state.update {
                    it.copy(
                        nodeState = MeshNodeState.Idle,
                        neighborCount = 0,
                        joined = false,
                        localUserId = "",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to stop mesh: ${e.message}") }
            }
        }
    }

    private fun sendMessage() {
        val node = meshNode ?: return
        val current = _state.value
        val text = current.messageToSend.trim()
        if (text.isEmpty() || !current.joined) return

        viewModelScope.launch {
            try {
                val envelope = MeshEnvelope.ChatMessage(
                    messageId = Uuid.random().toString(),
                    userId = current.localUserId,
                    displayName = current.localDisplayName,
                    timestamp = currentTimeMillis(),
                    text = text,
                )
                mergeEnvelope(envelope)
                node.broadcast(MeshEnvelope.encode(envelope))
                _state.update { it.copy(messageToSend = "") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to send message: ${e.message}") }
            }
        }
    }

    private suspend fun syncLedgerToNeighbors() {
        val node = meshNode ?: return
        // A newly connected neighbor only needs recent history to catch up, not the full
        // transcript - cap the rebroadcast snapshot at the last 10 messages (ADR 0016).
        val snapshot = ledger.toSyncSnapshot(maxMessages = LEDGER_SYNC_MESSAGE_LIMIT)
        runCatching { node.broadcast(MeshEnvelope.encode(snapshot)) }
    }

    /**
     * Asks connected neighbors to reply with their own ledger snapshot (ADR 0016). This is a
     * pull-based complement to the push-based triggers in [syncLedgerToNeighbors]: it doesn't
     * depend on another node's own edge-trigger/heartbeat/follow-up successfully reaching us -
     * only on this request getting through, after which the receiver replies right away.
     */
    private suspend fun requestLedgerFromNeighbors() {
        val node = meshNode ?: return
        val request = MeshEnvelope.LedgerRequest(requesterId = _state.value.localUserId)
        runCatching { node.broadcast(MeshEnvelope.encode(request)) }
    }

    private companion object {
        private const val LEDGER_SYNC_MESSAGE_LIMIT = 10

        /**
         * How often each node rebroadcasts its ledger snapshot to connected neighbors as a
         * self-healing fallback alongside the neighbor-count-increase trigger (see the comment
         * at the periodic resync launch site in [joinMesh]).
         */
        private const val LEDGER_SYNC_HEARTBEAT_INTERVAL_MS = 8_000L

        /**
         * Delay before a one-shot follow-up resync after a neighbor-count increase, to catch a
         * link that was still mid-MTU-negotiation/service-discovery at the moment of the
         * instant edge-triggered sync (see the comment at its launch site in [joinMesh]).
         */
        private const val FOLLOW_UP_SYNC_DELAY_MS = 3_000L
    }

    private fun updateMessageText(text: String) {
        _state.update { it.copy(messageToSend = text) }
    }

    /** Merges [envelope] into the local ledger and republishes derived UI state. */
    private fun mergeEnvelope(envelope: MeshEnvelope) {
        ledger = ledger.merge(envelope)
        val localUserId = _state.value.localUserId
        _state.update {
            it.copy(
                messages = ledger.messagesNewestFirst.map { message ->
                    ChatMessage(
                        id = message.messageId,
                        userId = message.userId,
                        displayName = message.displayName,
                        timestamp = message.timestamp,
                        text = message.text,
                        isOwnMessage = message.userId == localUserId,
                    )
                },
                participants = ledger.users.values
                    // A rejoin after this device's app was killed mints a brand-new random
                    // userId (no persisted identity, per ADR 0016), which would otherwise show
                    // the same person twice under the same display name. Collapse to the most
                    // recently announced identity per display name for display purposes only -
                    // message attribution still uses the untouched per-userId ledger above.
                    .groupBy { user -> user.displayName }
                    .map { (_, announcements) -> announcements.maxBy { user -> user.timestamp } }
                    .sortedBy { user -> user.displayName },
            )
        }
    }

    /** Clears the locally displayed transcript only; does not affect other nodes' ledgers. */
    private fun clearMessages() {
        ledger = ledger.copy(messages = emptyMap())
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
    data class UpdateDisplayNameInput(val text: String) : MeshDemoEvent
    data object JoinMesh : MeshDemoEvent
    data object StopMesh : MeshDemoEvent
    data object SendMessage : MeshDemoEvent
    data class UpdateMessageText(val text: String) : MeshDemoEvent
    data object ClearMessages : MeshDemoEvent
    data object DismissError : MeshDemoEvent
}
