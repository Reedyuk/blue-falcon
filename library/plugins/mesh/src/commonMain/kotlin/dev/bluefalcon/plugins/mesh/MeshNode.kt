package dev.bluefalcon.plugins.mesh

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.CharacteristicWriteType
import dev.bluefalcon.core.PeripheralConnectionState
import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattCharacteristicWriteRequest
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/**
 * A BLE mesh node that can relay messages between neighbors using both central and peripheral roles.
 *
 * `MeshNode` combines a [BlueFalcon] instance (central role, connecting to other nodes as peripherals)
 * and a [BlueFalconPeripheral] instance (peripheral role, accepting connections from other nodes as
 * centrals) to create a relay node capable of forwarding messages across a BLE mesh network.
 *
 * ## Message Relaying
 *
 * When a [MeshMessage] is received from any neighbor:
 * 1. It is checked against the dedup cache. If already seen, it is dropped.
 * 2. If the hop count has reached [MeshConfig.maxHopCount], it is dropped.
 * 3. Otherwise, it is emitted on [inboundMessages] for the application.
 * 4. The hop count is incremented and the message is relayed to all other connected neighbors.
 *
 * ## Usage
 *
 * ```kotlin
 * val central = BlueFalcon { engine = myEngine }
 * val peripheral = createBlueFalconPeripheral() // platform-specific
 *
 * val meshNode = MeshNode(
 *     central = central,
 *     peripheral = peripheral,
 *     config = MeshConfig().apply {
 *         maxHopCount = 5
 *         advertisedName = "MyMeshNode"
 *     }
 * )
 *
 * // Start mesh operations
 * meshNode.start()
 *
 * // Observe inbound messages
 * launch {
 *     meshNode.inboundMessages.collect { message ->
 *         println("Received from mesh: ${message.payload.decodeToString()}")
 *     }
 * }
 *
 * // Broadcast a message to the mesh
 * meshNode.broadcast("Hello mesh!".encodeToByteArray())
 *
 * // Stop when done
 * meshNode.stop()
 * ```
 *
 * @param central The BlueFalcon instance for central role (connecting to other mesh nodes)
 * @param peripheral The BlueFalconPeripheral instance for peripheral role (accepting connections)
 * @param config Configuration for mesh behavior
 * @param nodeUuid Unique identifier for this node. Defaults to a random UUID.
 */
class MeshNode(
    private val central: BlueFalcon,
    private val peripheral: BlueFalconPeripheral,
    private val config: MeshConfig = MeshConfig(),
    val nodeUuid: String = kotlin.uuid.Uuid.random().toString(),
) {
    private val _state = MutableStateFlow(MeshNodeState.Idle)

    /**
     * Current operational state of the mesh node.
     */
    val state: StateFlow<MeshNodeState> = _state.asStateFlow()

    private val _inboundMessages = MutableSharedFlow<MeshMessage>(extraBufferCapacity = 64)

    /**
     * Flow of messages received from the mesh network.
     *
     * Emits [MeshMessage]s that have passed deduplication and hop-count checks.
     * Messages originated by this node are not emitted here.
     */
    val inboundMessages: SharedFlow<MeshMessage> = _inboundMessages.asSharedFlow()

    private val dedupCache = DedupCache(
        maxSize = config.dedupCacheSize,
        ttl = config.dedupTtl,
    )

    // Per-session framers for reassembly (keyed by session ID for peripheral, peripheral UUID for central)
    private val framers = mutableMapOf<String, MeshFramer>()
    private val framersMutex = Mutex()

    // Connected central-role neighbors (peripherals we've connected to)
    private val centralNeighbors = mutableMapOf<String, BluetoothPeripheral>()
    private val centralNeighborsMutex = Mutex()

    private var meshScope: CoroutineScope? = null
    private var scanJob: Job? = null
    private var requestHandlerJob: Job? = null
    private var pruneJob: Job? = null

    // IDs for the mesh GATT service and characteristic
    private val meshServiceId = GattServiceId(config.meshServiceUuid)
    private val meshCharacteristicId = GattCharacteristicId(config.meshCharacteristicUuid)

    /**
     * Start the mesh node.
     *
     * This will:
     * 1. Start advertising as a peripheral with the mesh service
     * 2. Begin scanning for other mesh nodes (if [MeshConfig.autoConnectToNeighbors] is true)
     * 3. Start handling inbound GATT write requests and relaying messages
     */
    suspend fun start() {
        if (_state.value == MeshNodeState.Running) return

        _state.value = MeshNodeState.Running

        val scope = CoroutineScope(SupervisorJob() + central.engine.scope.coroutineContext)
        meshScope = scope

        // Start peripheral role with mesh service
        val advertiseConfig = AdvertiseConfig(
            localName = config.advertisedName,
            serviceUuids = listOf(config.meshServiceUuid.toString()),
            services = listOf(
                GattServiceConfig(
                    uuid = config.meshServiceUuid.toString(),
                    characteristics = listOf(
                        GattCharacteristicConfig(
                            uuid = config.meshCharacteristicUuid.toString(),
                            properties = setOf(
                                CharacteristicProperty.WRITE,
                                CharacteristicProperty.WRITE_NO_RESPONSE,
                                CharacteristicProperty.NOTIFY,
                            ),
                        )
                    )
                )
            ),
        )
        val peripheralConfig = PeripheralConfig(
            advertiseConfig = advertiseConfig,
        )
        peripheral.start(peripheralConfig)

        // Handle GATT write requests (inbound mesh messages from other centrals)
        requestHandlerJob = scope.launch {
            peripheral.requests.collect { request ->
                if (request is GattCharacteristicWriteRequest &&
                    request.characteristicId == meshCharacteristicId
                ) {
                    handleInboundFrame(
                        sourceId = request.sessionId.value,
                        sourceSession = request.session,
                        sourcePeripheral = null,
                        frame = request.value,
                    )

                    // Respond success if response required
                    request.response?.respond(
                        dev.bluefalcon.peripheral.GattResponseStatus.Success
                    )
                }
            }
        }

        // Start scanning for other mesh nodes
        if (config.autoConnectToNeighbors) {
            scanJob = scope.launch {
                startScanningForNeighbors()
            }
        }

        // Periodic dedup cache pruning
        pruneJob = scope.launch {
            while (isActive) {
                delay(config.dedupTtl / 2)
                dedupCache.prune()
            }
        }
    }

    /**
     * Stop the mesh node.
     *
     * Disconnects from all neighbors, stops advertising, and stops scanning.
     */
    suspend fun stop() {
        if (_state.value != MeshNodeState.Running) return

        _state.value = MeshNodeState.Stopping

        // Cancel all jobs
        scanJob?.cancel()
        requestHandlerJob?.cancel()
        pruneJob?.cancel()

        // Disconnect from central neighbors
        centralNeighborsMutex.withLock {
            centralNeighbors.values.forEach { neighbor ->
                runCatching { central.disconnect(neighbor) }
            }
            centralNeighbors.clear()
        }

        // Stop scanning
        runCatching { central.stopScanning() }

        // Stop peripheral
        runCatching { peripheral.stop() }

        // Clear state
        framersMutex.withLock { framers.clear() }
        dedupCache.clear()

        meshScope?.cancel()
        meshScope = null

        _state.value = MeshNodeState.Stopped
    }

    /**
     * Broadcast a message to all connected mesh neighbors.
     *
     * The message will be relayed by neighbors according to the flood-with-dedup strategy.
     *
     * @param payload The data to broadcast
     * @return The [MeshMessage] that was created and broadcast
     */
    suspend fun broadcast(payload: ByteArray): MeshMessage {
        val message = MeshMessage(
            id = MeshMessageId.random(),
            originUuid = nodeUuid,
            hopCount = 0,
            payload = payload,
        )

        // Add to dedup cache so we don't process our own message if it comes back
        dedupCache.add(message.id)

        // Relay to all neighbors
        relayToAllNeighbors(message, excludeSourceId = null)

        return message
    }

    private suspend fun startScanningForNeighbors() {
        // Set up peripheral discovery handling
        meshScope?.launch {
            central.peripherals.collect { peripherals ->
                // Filter for mesh service and connect to new neighbors
                peripherals.forEach { peripheral ->
                    val uuid = peripheral.uuid
                    val alreadyConnected = centralNeighborsMutex.withLock {
                        centralNeighbors.containsKey(uuid)
                    }

                    if (!alreadyConnected && shouldConnectToNeighbor()) {
                        connectToNeighbor(peripheral)
                    }
                }
            }
        }

        // Start scanning with mesh service filter
        central.scan(listOf(ServiceFilter(config.meshServiceUuid)))
    }

    private suspend fun shouldConnectToNeighbor(): Boolean {
        return centralNeighborsMutex.withLock {
            centralNeighbors.size < config.maxNeighborConnections
        }
    }

    private suspend fun connectToNeighbor(neighbor: BluetoothPeripheral) {
        val scope = meshScope ?: return

        scope.launch {
            try {
                central.connect(neighbor)

                // Wait for connection and add to neighbors
                centralNeighborsMutex.withLock {
                    centralNeighbors[neighbor.uuid] = neighbor
                }

                // Monitor connection state and remove on disconnect
                central.connectionStateFlow(neighbor).collect { state ->
                    if (state is PeripheralConnectionState.Disconnected) {
                        centralNeighborsMutex.withLock {
                            centralNeighbors.remove(neighbor.uuid)
                        }
                        framersMutex.withLock {
                            framers.remove(neighbor.uuid)
                        }
                    }
                }
            } catch (e: Exception) {
                // Connection failed - neighbor will be retried on next scan discovery
            }
        }
    }

    private suspend fun handleInboundFrame(
        sourceId: String,
        sourceSession: PeripheralSession?,
        sourcePeripheral: BluetoothPeripheral?,
        frame: ByteArray,
    ) {
        // Get or create framer for this source
        val framer = framersMutex.withLock {
            framers.getOrPut(sourceId) {
                // Use maximum update value length if available, otherwise default to 512
                val mtu = sourceSession?.maximumUpdateValueLength?.value ?: 512
                MeshFramer(maxFrameSize = mtu)
            }
        }

        when (val result = framer.parse(frame)) {
            is MeshFrameResult.Complete -> {
                processInboundMessage(result.message, sourceId)
            }
            is MeshFrameResult.Incomplete -> {
                // Waiting for more fragments
            }
            is MeshFrameResult.Invalid -> {
                // Log and ignore invalid frames
            }
        }
    }

    private suspend fun processInboundMessage(message: MeshMessage, sourceId: String) {
        // Check dedup cache
        if (dedupCache.contains(message.id)) {
            return // Already processed
        }

        // Add to dedup cache
        dedupCache.add(message.id)

        // Check hop count
        if (message.hopCount >= config.maxHopCount) {
            return // Exceeded max hops
        }

        // Emit to application (unless this is our own message)
        if (message.originUuid != nodeUuid) {
            _inboundMessages.tryEmit(message)
        }

        // Relay with incremented hop count
        val relayMessage = message.withIncrementedHopCount()
        relayToAllNeighbors(relayMessage, excludeSourceId = sourceId)
    }

    private suspend fun relayToAllNeighbors(message: MeshMessage, excludeSourceId: String?) {
        // Relay to peripheral sessions (other centrals connected to us)
        peripheral.sessions.value.forEach { session ->
            if (session.id.value != excludeSourceId) {
                relayToPeripheralSession(session, message)
            }
        }

        // Relay to central neighbors (peripherals we're connected to)
        val neighbors = centralNeighborsMutex.withLock { centralNeighbors.toMap() }
        neighbors.forEach { (uuid, neighbor) ->
            if (uuid != excludeSourceId) {
                relayToCentralNeighbor(neighbor, message)
            }
        }
    }

    private suspend fun relayToPeripheralSession(session: PeripheralSession, message: MeshMessage) {
        val mtu = session.maximumUpdateValueLength.value ?: 512
        val framer = MeshFramer(maxFrameSize = mtu)
        val frames = framer.frame(message)

        frames.forEach { frame ->
            session.notify(meshCharacteristicId, frame)
        }
    }

    private suspend fun relayToCentralNeighbor(neighbor: BluetoothPeripheral, message: MeshMessage) {
        // Find the mesh characteristic on the neighbor
        val service = neighbor.services.find { it.uuid == config.meshServiceUuid }
        val characteristic = service?.characteristics?.find { it.uuid == config.meshCharacteristicUuid }

        if (characteristic == null) {
            // Neighbor doesn't have mesh service discovered yet - skip
            return
        }

        val mtu = central.maximumWriteValueLength(neighbor, CharacteristicWriteType.WithResponse) ?: 512
        val framer = MeshFramer(maxFrameSize = mtu)
        val frames = framer.frame(message)

        frames.forEach { frame ->
            central.writeCharacteristic(
                neighbor,
                characteristic,
                frame,
                CharacteristicWriteType.WithResponse,
            )
        }
    }

    companion object {
        /**
         * Create a MeshNode with the given configuration.
         */
        fun create(
            central: BlueFalcon,
            peripheral: BlueFalconPeripheral,
            configure: MeshConfig.() -> Unit = {},
        ): MeshNode {
            val config = MeshConfig().apply(configure)
            return MeshNode(central, peripheral, config)
        }
    }
}

/**
 * DSL function to create a [MeshNode] with configuration.
 */
fun meshNode(
    central: BlueFalcon,
    peripheral: BlueFalconPeripheral,
    configure: MeshConfig.() -> Unit = {},
): MeshNode = MeshNode.create(central, peripheral, configure)
