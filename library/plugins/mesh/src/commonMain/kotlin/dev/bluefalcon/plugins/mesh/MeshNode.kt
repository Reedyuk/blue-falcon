package dev.bluefalcon.plugins.mesh

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import dev.bluefalcon.core.Logger
import dev.bluefalcon.core.PeripheralConnectionState
import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattCharacteristicWriteRequest
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServerRequest
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
 * @param logger Optional logger for diagnosing connection/relay issues. Defaults to none.
 */
class MeshNode(
    private val central: BlueFalcon,
    private val peripheral: BlueFalconPeripheral,
    private val config: MeshConfig = MeshConfig(),
    val nodeUuid: String = kotlin.uuid.Uuid.random().toString(),
    private val logger: Logger? = null,
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

    // Peripherals with a connect attempt currently in flight. Needed in addition to
    // centralNeighbors because central.peripherals is a StateFlow that re-emits on
    // every scan/RSSI update — without this guard, a peripheral discovered while its
    // own connect()/MTU/discovery/subscribe sequence is still in flight (which can
    // take multiple seconds) would trigger a second, concurrent connectToNeighbor()
    // call for the same peripheral before it's added to centralNeighbors, racing two
    // connect attempts against each other and causing flapping/incorrect neighbor
    // counts (most visible on iOS/CoreBluetooth, which does not tolerate a second
    // connect() call on an already-connecting peripheral).
    private val pendingConnections = mutableSetOf<String>()

    // Serialises outbound sends per neighbour (keyed by peripheral uuid / session id).
    // Two concurrent sends to the same neighbour - e.g. this node's own broadcast()
    // racing a relay of an inbound message - would otherwise interleave the frames of
    // multi-frame messages (breaking reassembly), and on Apple platforms the second
    // write is rejected outright because only one "with response" write may be in
    // flight per connection.
    private val sendMutexes = mutableMapOf<String, Mutex>()
    private val sendMutexesMutex = Mutex()

    private suspend fun sendMutexFor(id: String): Mutex =
        sendMutexesMutex.withLock { sendMutexes.getOrPut(id) { Mutex() } }

    private val _neighborCount = MutableStateFlow(0)

    /**
     * Number of currently connected neighbors, counting both directions:
     * - Neighbors this node connected to as a central (outbound).
     * - Neighbors connected to this node as a peripheral (inbound), i.e.
     *   [BlueFalconPeripheral.sessions].
     *
     * Updated automatically whenever a neighbor connects or disconnects in either
     * direction. Useful for displaying mesh size in UI.
     */
    val neighborCount: StateFlow<Int> = _neighborCount.asStateFlow()

    private var meshScope: CoroutineScope? = null
    private var scanJob: Job? = null
    private var neighborCountJob: Job? = null
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

        // Inherit the engine's dispatcher but NOT its Job: `SupervisorJob() + context`
        // lets the engine's own Job win the Job key, so cancelling this scope in stop()
        // would tear down the shared engine scope and leave the central unusable for
        // every subsequent start(). Add the supervisor last so it owns the Job key.
        val scope = CoroutineScope(central.engine.scope.coroutineContext + SupervisorJob())
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
                // Never let a single malformed/unexpected request escape: an exception
                // here would cancel this collector, after which nothing drains
                // peripheral.requests. Inbound writes would then go unanswered, and
                // because ATT allows only one outstanding transaction per link that
                // silently wedges every peer's ATT channel - the node stops receiving
                // entirely and peers stall until their transactions time out.
                runCatching { handleRequest(request) }
                    .onFailure { logger?.error("peripheral.requests: failed to handle $request", it) }
            }
        }

        // Start scanning for other mesh nodes
        if (config.autoConnectToNeighbors) {
            scanJob = scope.launch {
                startScanningForNeighbors()
            }
        }

        // Keep neighborCount in sync with both inbound (peripheral sessions) and
        // outbound (central) neighbor connections.
        neighborCountJob = scope.launch {
            peripheral.sessions.collect {
                updateNeighborCount()
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

    private suspend fun handleRequest(request: GattServerRequest) {
        logger?.debug("peripheral.requests: received $request")

        val meshWrite = (request as? GattCharacteristicWriteRequest)
            ?.takeIf { it.characteristicId == meshCharacteristicId }

        // Every request carrying a response handle MUST be answered - including ones
        // the mesh itself has no interest in, most importantly the CCCD descriptor
        // write a neighbour performs when it subscribes for notifications. ATT is
        // strictly one outstanding transaction at a time, so an unanswered request
        // stalls the peer's entire ATT channel: its subsequent characteristic writes
        // are never transmitted, and the link is eventually torn down on transaction
        // timeout. Android surfaces the CCCD write here (and only commits the
        // subscription once it is answered), whereas CoreBluetooth answers it
        // internally - which is why leaving it unanswered broke exactly one direction
        // of the mesh.
        //
        // Answer before doing any relaying so the peer's ATT channel is released
        // immediately rather than being held for the duration of a relay (which itself
        // awaits ATT writes to other neighbours, and would otherwise deadlock a mesh of
        // three or more nodes).
        request.response?.respond(GattResponseStatus.Success)

        if (meshWrite != null) {
            logger?.debug(
                "peripheral.requests: mesh write from session=${meshWrite.sessionId.value} " +
                    "(${meshWrite.value.size} bytes)"
            )
            handleInboundFrame(
                sourceId = meshWrite.sessionId.value,
                frame = meshWrite.value,
            )
        }
    }

    private suspend fun updateNeighborCount() {
        val outboundCount = centralNeighborsMutex.withLock { centralNeighbors.size }
        _neighborCount.value = outboundCount + peripheral.sessions.value.size
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
        neighborCountJob?.cancel()
        requestHandlerJob?.cancel()
        pruneJob?.cancel()

        // Disconnect from central neighbors
        centralNeighborsMutex.withLock {
            centralNeighbors.values.forEach { neighbor ->
                runCatching { central.disconnect(neighbor) }
            }
            centralNeighbors.clear()
            pendingConnections.clear()
        }

        // Stop scanning
        runCatching { central.stopScanning() }

        // Stop peripheral
        runCatching { peripheral.stop() }

        // Clear state
        framersMutex.withLock { framers.clear() }
        sendMutexesMutex.withLock { sendMutexes.clear() }
        dedupCache.clear()
        _neighborCount.value = 0

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
        // `central.peripherals` is a single shared, ever-growing set for the lifetime of the
        // owning BlueFalcon instance - it is *not* scoped to the current scan() call, and it is
        // never pruned when a new/different ServiceFilter is applied. If the app's general
        // "Central" scan screen (typically unfiltered, discovering every nearby BLE device) has
        // already populated it, every one of those unrelated devices - headphones, watches,
        // etc. - would otherwise be treated below as a freshly-discovered mesh neighbor and
        // dialled, even though they never advertise the mesh service and the physical test
        // environment has no other mesh node at all. Clear it before starting this node's own
        // filtered scan so only devices actually discovered under the mesh ServiceFilter below
        // are considered.
        central.clearPeripherals()

        // Set up peripheral discovery handling
        meshScope?.launch {
            central.peripherals.collect { peripherals ->
                // Filter for mesh service and connect to new neighbors
                peripherals.forEach { peripheral ->
                    val uuid = peripheral.uuid
                    val shouldConnect = centralNeighborsMutex.withLock {
                        if (centralNeighbors.containsKey(uuid) || pendingConnections.contains(uuid)) {
                            false
                        } else if (centralNeighbors.size < config.maxNeighborConnections) {
                            pendingConnections.add(uuid)
                            true
                        } else {
                            false
                        }
                    }

                    if (shouldConnect) {
                        connectToNeighbor(peripheral)
                    }
                }
            }
        }

        // Start scanning with mesh service filter
        central.scan(listOf(ServiceFilter(config.meshServiceUuid)))
    }

    /**
     * Waits (with a bounded timeout) for [BlueFalcon.characteristicWriteCapabilities]
     * to reflect a negotiated write size sufficient for at least one mesh frame
     * ([MeshFramer.HEADER_SIZE] + 1 byte of payload). MTU negotiation is asynchronous
     * on Android (and may simply not occur, e.g. if the remote side rejects it), so
     * frames must not be sent using the stale pre-negotiation default (20 bytes).
     */
    private suspend fun awaitSufficientWriteCapability(neighbor: BluetoothPeripheral) {
        val requiredLength = MeshFramer.HEADER_SIZE + 1
        withTimeoutOrNull(5.seconds) {
            while (
                (central.maximumWriteValueLength(neighbor, CharacteristicWriteType.WithResponse) ?: 0) <
                requiredLength
            ) {
                delay(100)
            }
        }
    }

    /**
     * Waits (with a bounded timeout) for [neighbor]'s connection state to reach
     * [PeripheralConnectionState.Connected] or [PeripheralConnectionState.Ready].
     *
     * [BlueFalcon.connect] only awaits the platform *request* being issued — on Apple
     * platforms in particular, the actual link establishment is confirmed
     * asynchronously via `CBCentralManagerDelegate.didConnectPeripheral`, which can
     * arrive well after `connect()` returns. Proceeding straight into MTU
     * negotiation/service discovery before the link is actually up causes those calls
     * to silently no-op (both guard on `CBPeripheralStateConnected`), leaving the
     * neighbor stuck with no services/characteristics and never counted.
     *
     * @return true once connected, false if the timeout elapsed or the peripheral
     * disconnected/failed to connect first.
     */
    private object ConnectedSignal : Throwable()
    private object DisconnectedSignal : Throwable()

    private suspend fun awaitConnectedOrFail(neighbor: BluetoothPeripheral): Boolean {
        return try {
            withTimeoutOrNull(10.seconds) {
                central.connectionStateFlow(neighbor).collect { state ->
                    when (state) {
                        is PeripheralConnectionState.Connected,
                        is PeripheralConnectionState.Ready -> throw ConnectedSignal
                        is PeripheralConnectionState.Disconnected -> throw DisconnectedSignal
                        else -> Unit
                    }
                }
            }
            false // timed out without reaching Connected/Ready or Disconnected
        } catch (c: ConnectedSignal) {
            true
        } catch (d: DisconnectedSignal) {
            false
        }
    }

    private suspend fun connectToNeighbor(neighbor: BluetoothPeripheral) {
        val scope = meshScope ?: return

        scope.launch {
            try {
                central.connect(neighbor)
                logger?.debug("connectToNeighbor: connect() requested for ${neighbor.uuid}")

                // Wait for the link to actually be established before proceeding -
                // connect() only awaits the request being issued, not the platform's
                // asynchronous confirmation (see awaitConnectedOrFail's KDoc).
                if (!awaitConnectedOrFail(neighbor)) {
                    logger?.debug("connectToNeighbor: ${neighbor.uuid} failed to reach Connected/Ready")
                    centralNeighborsMutex.withLock {
                        pendingConnections.remove(neighbor.uuid)
                    }
                    return@launch
                }
                logger?.debug("connectToNeighbor: ${neighbor.uuid} is Connected/Ready")

                // The mesh frame header alone (81 bytes) is larger than the default BLE
                // ATT payload (20 bytes on Android before MTU negotiation), so a larger
                // MTU must actually be negotiated *and confirmed* before any frame is
                // sent. changeMTU() only enqueues the platform's MTU request — it does
                // not wait for the result — so await characteristicWriteCapabilities
                // until it reflects a large-enough negotiated size, falling back to
                // whatever was actually negotiated (which may be smaller, e.g. if the
                // remote peer or platform caps it) after a short timeout.
                runCatching { central.changeMTU(neighbor, config.preferredMtu) }
                awaitSufficientWriteCapability(neighbor)
                logger?.debug(
                    "connectToNeighbor: ${neighbor.uuid} write capability=" +
                        "${central.maximumWriteValueLength(neighbor, CharacteristicWriteType.WithResponse)}"
                )

                // Register the neighbor as soon as it's write-capable so outbound
                // relays can reach it immediately - subscribing to notifications
                // (needed only for *receiving* from this neighbor) can take several
                // seconds to be confirmed by the platform (e.g. CoreBluetooth's
                // subscription ack), and previously the neighbor wasn't added to
                // centralNeighbors until that finished, silently dropping any
                // messages broadcast during that window.
                centralNeighborsMutex.withLock {
                    centralNeighbors[neighbor.uuid] = neighbor
                    pendingConnections.remove(neighbor.uuid)
                }
                logger?.debug(
                    "connectToNeighbor: ${neighbor.uuid} added to centralNeighbors " +
                        "(size=${centralNeighborsMutex.withLock { centralNeighbors.size }})"
                )
                updateNeighborCount()

                // Subscribe to the mesh characteristic's notifications so this node
                // actually receives messages relayed by the neighbor. Without this,
                // the connection is write-only: this node can send to the neighbor
                // but the neighbor's notify() calls have no subscriber and are never
                // delivered here.
                subscribeToNeighborNotifications(neighbor, scope)

                // Monitor connection state and remove on disconnect
                central.connectionStateFlow(neighbor).collect { state ->
                    if (state is PeripheralConnectionState.Disconnected) {
                        centralNeighborsMutex.withLock {
                            centralNeighbors.remove(neighbor.uuid)
                            pendingConnections.remove(neighbor.uuid)
                        }
                        framersMutex.withLock {
                            framers.remove(neighbor.uuid)
                        }
                        sendMutexesMutex.withLock {
                            sendMutexes.remove(neighbor.uuid)
                        }
                        updateNeighborCount()
                    }
                }
            } catch (e: Exception) {
                // Connection failed - neighbor will be retried on next scan discovery.
                // Clear the pending marker so a future scan re-discovery can retry.
                centralNeighborsMutex.withLock {
                    pendingConnections.remove(neighbor.uuid)
                }
            }
        }
    }

    /**
     * Waits for the mesh characteristic to be discovered on [neighbor] (services are
     * discovered automatically on connect), then enables notifications on it and
     * forwards received frames into [handleInboundFrame].
     */
    private suspend fun subscribeToNeighborNotifications(neighbor: BluetoothPeripheral, scope: CoroutineScope) {
        // Auto-discovery of services (and, on Android, characteristics in the same
        // pass) after connect is only implemented by the Android engine; on Apple
        // platforms `neighbor.services` stays empty forever, and even once services
        // are discovered each service's `characteristics` list stays empty until
        // discoverCharacteristics() is explicitly called per-service. Both calls are
        // harmless no-ops (or fast no-op waits) on platforms that already
        // auto-discover both in one shot.
        runCatching { central.discoverServices(neighbor, listOf(config.meshServiceUuid)) }

        val service = withTimeoutOrNull(10.seconds) {
            var found: dev.bluefalcon.core.BluetoothService? = null
            while (found == null) {
                found = neighbor.services.find { it.uuid == config.meshServiceUuid }
                if (found == null) delay(100)
            }
            found
        }
        if (service == null) {
            logger?.debug("subscribeToNeighborNotifications: ${neighbor.uuid} mesh service never discovered")
            return
        }

        runCatching {
            central.discoverCharacteristics(neighbor, service, listOf(config.meshCharacteristicUuid))
        }

        val characteristic = withTimeoutOrNull(10.seconds) {
            var found: BluetoothCharacteristic? = null
            while (found == null) {
                found = neighbor.services
                    .find { it.uuid == config.meshServiceUuid }
                    ?.characteristics
                    ?.find { it.uuid == config.meshCharacteristicUuid }
                if (found == null) delay(100)
            }
            found
        }
        if (characteristic == null) {
            logger?.debug(
                "subscribeToNeighborNotifications: ${neighbor.uuid} mesh characteristic never discovered"
            )
            return
        }
        logger?.debug("subscribeToNeighborNotifications: ${neighbor.uuid} characteristic discovered, subscribing")

        scope.launch {
            characteristic.notifications.collect { frame ->
                logger?.debug(
                    "subscribeToNeighborNotifications: notification from ${neighbor.uuid} (${frame.size} bytes)"
                )
                handleInboundFrame(
                    sourceId = neighbor.uuid,
                    frame = frame,
                )
            }
        }

        central.setNotificationSubscription(neighbor, characteristic, enabled = true)
    }

    private suspend fun handleInboundFrame(
        sourceId: String,
        frame: ByteArray,
    ) {
        // Reassembly framer for this source. Deliberately independent of the peer's
        // MTU: parsing never needs it, and deriving it from the peer's MTU used to
        // throw whenever that MTU was smaller than one frame header - which aborted
        // the frame, cancelled the collector it ran on, and silently took down this
        // node's entire receive path.
        val framer = framersMutex.withLock {
            framers.getOrPut(sourceId) { MeshFramer.forReassembly() }
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
        // Relay off the inbound path: relaying awaits ATT writes to other neighbours,
        // and must not hold up reading/answering further inbound frames (fragments of
        // a following message, another neighbour's write, ...).
        val scope = meshScope
        if (scope != null) {
            scope.launch { relayToAllNeighbors(relayMessage, excludeSourceId = sourceId) }
        } else {
            relayToAllNeighbors(relayMessage, excludeSourceId = sourceId)
        }
    }

    private suspend fun relayToAllNeighbors(message: MeshMessage, excludeSourceId: String?) {
        // Relay to peripheral sessions (other centrals connected to us)
        val sessions = peripheral.sessions.value
        val neighbors = centralNeighborsMutex.withLock { centralNeighbors.toMap() }
        logger?.debug(
            "relayToAllNeighbors: sessions=${sessions.map { it.id.value }} " +
                "centralNeighbors=${neighbors.keys} excludeSourceId=$excludeSourceId"
        )
        sessions.forEach { session ->
            if (session.id.value != excludeSourceId) {
                relayToPeripheralSession(session, message)
            }
        }

        // Relay to central neighbors (peripherals we're connected to)
        neighbors.forEach { (uuid, neighbor) ->
            if (uuid != excludeSourceId) {
                relayToCentralNeighbor(neighbor, message)
            }
        }
    }

    private suspend fun relayToPeripheralSession(session: PeripheralSession, message: MeshMessage) {
        val mtu = session.maximumUpdateValueLength.value ?: 512
        if (mtu < MeshFramer.HEADER_SIZE + 1) {
            // Peripheral-side MTU not yet negotiated large enough for a mesh frame -
            // skip this neighbor for now rather than failing the whole broadcast.
            logger?.debug(
                "relayToPeripheralSession: skipping session ${session.id.value}, " +
                    "mtu=$mtu too small for a mesh frame"
            )
            return
        }
        val framer = MeshFramer(maxFrameSize = mtu)
        val frames = framer.frame(message)

        sendMutexFor(session.id.value).withLock {
            frames.forEach { frame ->
                val result = session.notify(meshCharacteristicId, frame)
                logger?.debug(
                    "relayToPeripheralSession: notified session ${session.id.value} " +
                        "(${frame.size} bytes) -> $result"
                )
            }
        }
    }

    private suspend fun relayToCentralNeighbor(neighbor: BluetoothPeripheral, message: MeshMessage) {
        // Find the mesh characteristic on the neighbor
        val service = neighbor.services.find { it.uuid == config.meshServiceUuid }
        val characteristic = service?.characteristics?.find { it.uuid == config.meshCharacteristicUuid }

        if (characteristic == null) {
            // Neighbor doesn't have mesh service discovered yet - skip
            logger?.debug(
                "relayToCentralNeighbor: skipping ${neighbor.uuid}, mesh characteristic not " +
                    "discovered yet (services=${neighbor.services.map { it.uuid }})"
            )
            return
        }

        val mtu = central.maximumWriteValueLength(neighbor, CharacteristicWriteType.WithResponse) ?: 512
        if (mtu < MeshFramer.HEADER_SIZE + 1) {
            // MTU negotiation for this neighbor hasn't completed with a sufficient
            // size yet - skip for now rather than failing the whole broadcast; it
            // will be retried on the next message once negotiation catches up.
            logger?.debug(
                "relayToCentralNeighbor: skipping ${neighbor.uuid}, mtu=$mtu too small for a mesh frame"
            )
            return
        }
        val framer = MeshFramer(maxFrameSize = mtu)
        val frames = framer.frame(message)

        sendMutexFor(neighbor.uuid).withLock {
            frames.forEach { frame ->
                // Apple's central write path only allows one in-flight "with response"
                // write per connection; a write issued while another is outstanding is
                // rejected immediately as Backpressured rather than being queued. The
                // per-neighbour mutex above prevents this node from colliding with
                // itself, but retry anyway to absorb a write left in flight by an
                // earlier, already-cancelled send.
                var result = central.writeCharacteristic(
                    neighbor,
                    characteristic,
                    frame,
                    CharacteristicWriteType.WithResponse,
                )
                var attempt = 0
                while (
                    result == CharacteristicWriteResult.Backpressured &&
                    attempt < BACKPRESSURE_RETRY_LIMIT
                ) {
                    attempt++
                    delay(BACKPRESSURE_RETRY_DELAY_MS)
                    result = central.writeCharacteristic(
                        neighbor,
                        characteristic,
                        frame,
                        CharacteristicWriteType.WithResponse,
                    )
                }
                logger?.debug(
                    "relayToCentralNeighbor: wrote to ${neighbor.uuid} (${frame.size} bytes) -> " +
                        "$result${if (attempt > 0) " (after $attempt retries)" else ""}"
                )
            }
        }
    }

    companion object {
        /** Max retries when a central write is rejected as [CharacteristicWriteResult.Backpressured]. */
        private const val BACKPRESSURE_RETRY_LIMIT = 5

        /** Delay between backpressure retries. */
        private const val BACKPRESSURE_RETRY_DELAY_MS = 50L

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
