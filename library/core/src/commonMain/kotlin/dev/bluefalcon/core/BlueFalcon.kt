package dev.bluefalcon.core

import dev.bluefalcon.core.plugin.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Read-only observation of one facade teardown. [await] preserves cleanup failures;
 * cancelling its caller ends only that wait and cannot cancel the underlying cleanup.
 */
class BlueFalconCloseCompletion internal constructor(private val cleanup: Deferred<Unit>) {
    val isCompleted: Boolean get() = cleanup.isCompleted
    suspend fun await() { cleanup.await() }
}

/**
 * Main Blue Falcon client that wraps an engine and provides plugin support
 *
 * [close] stops this facade's collectors, derived flows, and forwarded suspend tasks.
 * [ownsEngine] defaults to false so externally shared engines remain available to other clients.
 * Set it to true only for a final engine owner; the engine must implement [ClosableBlueFalconEngine].
 */
class BlueFalcon(
    val engine: BlueFalconEngine,
    val ownsEngine: Boolean = false,
) : BlueFalconClient {
    constructor(engine: BlueFalconEngine) : this(engine, ownsEngine = false)
    private val ownedEngine = if (ownsEngine) {
        require(engine is ClosableBlueFalconEngine) { "Owned engine must implement ClosableBlueFalconEngine" }
        engine
    } else null
    private val facadeJob = SupervisorJob(engine.scope.coroutineContext[Job])
    private val facadeScope = CoroutineScope(engine.scope.coroutineContext + facadeJob)
    private data class Admission(val closing: Boolean = false, val active: Int = 0)
    private val synchronousAdmission = MutableStateFlow(Admission())
    private val closeCompletion = MutableStateFlow<BlueFalconCloseCompletion?>(null)
    private val storageFailure = MutableStateFlow<IllegalStateException?>(null)
    // Cleanup cannot be a child of the facade it joins, the engine it closes, or its caller.
    private val closeScope = object : CoroutineScope {
        override val coroutineContext = engine.scope.coroutineContext.minusKey(Job)
    }

    private fun ensureOpen() {
        check(!synchronousAdmission.value.closing && facadeJob.isActive) { "BlueFalcon is closing or closed" }
    }

    private fun <T> synchronous(block: () -> T): T {
        while (true) {
            val current = synchronousAdmission.value
            check(!current.closing && facadeJob.isActive) { "BlueFalcon is closing or closed" }
            if (synchronousAdmission.compareAndSet(current, current.copy(active = current.active + 1))) break
        }
        return try { block() } finally {
            synchronousAdmission.update { it.copy(active = it.active - 1) }
        }
    }

    /**
     * Start terminal cleanup without awaiting it. Use this from a plugin/event callback:
     * cancelling the facade also cancels that callback, so awaiting close there cancels its waiter.
     * The returned completion is shared by concurrent requests and retains cleanup failures.
     */
    fun requestClose(): BlueFalconCloseCompletion {
        synchronousAdmission.update { it.copy(closing = true) }
        closeCompletion.value?.let { return it }
        val cleanup = closeScope.async(start = CoroutineStart.LAZY) {
            facadeJob.cancel()
            try {
                synchronousAdmission.first { it.active == 0 }
                ownedEngine?.close()
            } finally {
                try { facadeJob.join() } finally { connectionStateStore.clear() }
            }
            storageFailure.value?.let { throw it }
            Unit
        }
        val completion = BlueFalconCloseCompletion(cleanup)
        if (closeCompletion.compareAndSet(null, completion)) {
            cleanup.start()
            return completion
        }
        cleanup.cancel()
        return checkNotNull(closeCompletion.value)
    }

    /** Await terminal cleanup. Caller cancellation stops only this wait, not cleanup. */
    suspend fun close() { requestClose().await() }

    private suspend fun <T> owned(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        ensureOpen()
        val task = facadeScope.async {
            ensureOpen()
            val result = block()
            currentCoroutineContext().ensureActive()
            result
        }
        return try { task.await() } finally { task.cancel() }
    }

    private suspend fun <T> engineResult(block: suspend () -> T): Result<T> =
        try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { Result.failure(failure) }

    /**
     * Plugin registry for managing installed plugins
     */
    val plugins: PluginRegistry = PluginRegistry(this).also { it.aroundInstall = { action -> synchronous(action) } }

    /**
     * Backing store for the structured per-peripheral connection state machine (ADR 0008),
     * keyed by [BluetoothPeripheral.uuid].
     */
    private val connectionStateStore = ConnectionStateStore()
    private val _connectionStates = connectionStateStore.states
    /** Retained state keys (maximum256) and rejected updates; inactive history is evicted oldest first. */
    val connectionStateStorageStatus: ConnectionStateStorageStatus get() = connectionStateStore.status

    private fun publishConnectionState(uuid: String, retireOnOverflow: Boolean = true, transform: (PeripheralConnectionState?) -> PeripheralConnectionState?) {
        if (!connectionStateStore.update(uuid, transform) && retireOnOverflow) {
            storageFailure.compareAndSet(null, IllegalStateException("Connection state storage capacity exceeded"))
            requestClose()
        }
    }

    init {
        facadeScope.launch {
            engine.characteristicNotifications.collect { notification ->
                plugins.dispatchNotification(
                    NotificationCall(
                        peripheral = notification.peripheral,
                        characteristic = notification.characteristic,
                        value = notification.value
                    )
                )
            }
        }

        facadeScope.launch {
            engine.connectionStateUpdates.collect { update ->
                val uuid = update.peripheral.uuid
                when (update.state) {
                    BluetoothPeripheralState.Connected -> {
                        publishConnectionState(uuid) { PeripheralConnectionState.Connected }
                    }
                    BluetoothPeripheralState.Disconnected -> {
                        // Derive the reason inside update() so the read-modify-write is
                        // atomic: the lambda is re-run if another updater wins the CAS,
                        // which matters now that engine work is no longer serialised
                        // onto a single thread.
                        publishConnectionState(uuid, retireOnOverflow = false) { current ->
                            val reason = when (current) {
                                is PeripheralConnectionState.Disconnecting -> DisconnectReason.UserInitiated
                                is PeripheralConnectionState.Connecting -> DisconnectReason.ConnectFailed(
                                    BluetoothUnknownException()
                                )
                                is PeripheralConnectionState.Connected,
                                is PeripheralConnectionState.Ready -> DisconnectReason.Unexpected
                                else -> null
                            }
                            PeripheralConnectionState.Disconnected(reason)
                        }
                    }
                    BluetoothPeripheralState.Connecting -> {
                        publishConnectionState(uuid) { PeripheralConnectionState.Connecting }
                    }
                    BluetoothPeripheralState.Disconnecting -> {
                        publishConnectionState(uuid) { PeripheralConnectionState.Disconnecting }
                    }
                    BluetoothPeripheralState.Unknown -> Unit
                }
            }
        }

        facadeScope.launch {
            engine.serviceDiscoveryUpdates.collect { update ->
                if (update.phase != ServiceDiscoveryPhase.ServicesDiscovered) return@collect
                val uuid = update.peripheral.uuid
                publishConnectionState(uuid) { current ->
                    if (current == PeripheralConnectionState.Connected) PeripheralConnectionState.Ready else current
                }
            }
        }
    }
    
    /**
     * Delegated properties from engine
     */
    val peripherals: StateFlow<Set<BluetoothPeripheral>> get() = engine.peripherals
    val managerState: StateFlow<BluetoothManagerState> get() = engine.managerState
    val isScanning: Boolean get() = engine.isScanning
    val rssiUpdates: SharedFlow<Pair<String, Float>> get() = engine.rssiUpdates
    val centralCapabilities: CentralCapabilities get() = engine.centralCapabilities
    val characteristicWriteCapabilities:
        StateFlow<Map<CharacteristicWriteKey, CharacteristicWriteCapability>>
        get() = engine.characteristicWriteCapabilities
    val characteristicWriteReady: SharedFlow<CharacteristicWriteReady>
        get() = engine.characteristicWriteReady
    val notificationSubscriptionUpdates: SharedFlow<NotificationSubscriptionUpdate>
        get() = engine.notificationSubscriptionUpdates

    /**
     * Reactive stream of bond/pairing state changes, delegated from the engine.
     */
    val bondStateUpdates: SharedFlow<BondStateUpdate> get() = engine.bondStateUpdates

    /**
     * Reactive stream of peripheral connection state changes.
     *
     * Subscribe to this flow to be notified when a peripheral connects or disconnects.
     * Do **not** rely on polling [connectionState] immediately after calling [connect] —
     * BLE connections are asynchronous and [connectionState] will still return
     * [BluetoothPeripheralState.Disconnected] until the platform callback fires.
     *
     * ```kotlin
     * launch {
     *     blueFalcon.connectionStateUpdates.collect { update ->
     *         when (update.state) {
     *             BluetoothPeripheralState.Connected    -> println("${update.peripheral.name} connected")
     *             BluetoothPeripheralState.Disconnected -> println("${update.peripheral.name} disconnected")
     *             else -> Unit
     *         }
     *     }
     * }
     * ```
     */
    val connectionStateUpdates: SharedFlow<ConnectionStateUpdate> get() = engine.connectionStateUpdates

    /**
     * Reactive stream of GATT service and characteristic discovery events.
     *
     * Subscribe to this flow to be notified when services or characteristics become available
     * without polling [BluetoothPeripheral.services] or inserting arbitrary delays.
     *
     * ```kotlin
     * launch {
     *     blueFalcon.serviceDiscoveryUpdates
     *         .filter { it.peripheral.uuid == targetUuid }
     *         .collect { update ->
     *             when (update.phase) {
     *                 ServiceDiscoveryPhase.ServicesDiscovered ->
     *                     update.peripheral.services.forEach {
     *                         blueFalcon.discoverCharacteristics(update.peripheral, it)
     *                     }
     *                 ServiceDiscoveryPhase.CharacteristicsDiscovered ->
     *                     println("Characteristics ready for ${update.service?.uuid}")
     *             }
     *         }
     * }
     * ```
     */
    val serviceDiscoveryUpdates: SharedFlow<ServiceDiscoveryUpdate> get() = engine.serviceDiscoveryUpdates

    /**
     * Structured, per-peripheral connection state (ADR 0008), keyed by [BluetoothPeripheral.uuid].
     *
     * Retains at most256 peer keys. Oldest disconnected history is evicted first;
     * active states are never evicted. A new connect operation rejects when all slots
     * are active; callback overflow terminally closes this facade and its owned engine,
     * with failure observable from [close]. Successful teardown clears retained history.
     *
     * Derived from [connectionStateUpdates] and [serviceDiscoveryUpdates]. Prefer
     * [connectionStateFlow] or [peripheralState] for working with a single peripheral.
     */
    val connectionStates: StateFlow<Map<String, PeripheralConnectionState>> = _connectionStates

    /**
     * The current, structured connection state of [peripheral] (ADR 0008).
     *
     * Unlike [connectionState], this folds in GATT service discovery and typed disconnect
     * reasons. Returns [PeripheralConnectionState.Disconnected] with a `null` reason for a
     * peripheral that has never been connected to.
     */
    fun peripheralState(peripheral: BluetoothPeripheral): PeripheralConnectionState =
        _connectionStates.value[peripheral.uuid] ?: PeripheralConnectionState.Disconnected()

    /**
     * A [StateFlow] of [peripheral]'s structured connection state (ADR 0008).
     *
     * Unlike [connectionStateUpdates] (a `SharedFlow` with no replay), a collector that
     * subscribes after [peripheral] already connected immediately observes the current state
     * instead of waiting for the next transition.
     *
     * ```kotlin
     * launch {
     *     blueFalcon.connectionStateFlow(peripheral).collect { state ->
     *         when (state) {
     *             is PeripheralConnectionState.Ready -> println("Ready to use ${peripheral.name}")
     *             is PeripheralConnectionState.Disconnected -> println("Disconnected: ${state.reason}")
     *             else -> Unit
     *         }
     *     }
     * }
     * ```
     */
    fun connectionStateFlow(peripheral: BluetoothPeripheral): StateFlow<PeripheralConnectionState> {
        ensureOpen()
        return _connectionStates
            .map { it[peripheral.uuid] ?: PeripheralConnectionState.Disconnected() }
            .stateIn(facadeScope, SharingStarted.Eagerly, peripheralState(peripheral))
    }
    
    /**
     * Whether the underlying platform can enumerate Bluetooth adapters and switch between them.
     */
    val supportsAdapterSelection: Boolean get() = engine.supportsAdapterSelection

    /**
     * The adapter currently used by the engine, or null when the platform does not expose
     * adapter selection.
     */
    val selectedAdapter: BluetoothAdapter? get() = engine.selectedAdapter

    /**
     * Enumerate the Bluetooth adapters available on the host.
     *
     * Returns an empty list on platforms without adapter enumeration support.
     *
     * ```kotlin
     * val adapters = blueFalcon.adapters()
     * adapters.firstOrNull { it.name.contains("USB") }?.let {
     *     blueFalcon.selectAdapter(it.identifier)
     * }
     * ```
     */
    suspend fun adapters(): List<BluetoothAdapter> = owned { engine.adapters() }

    /**
     * Select the adapter that subsequent Bluetooth operations should use.
     *
     * @param identifier The [BluetoothAdapter.identifier] of an adapter returned by [adapters]
     */
    suspend fun selectAdapter(identifier: String): AdapterSelectionResult =
        owned { engine.selectAdapter(identifier) }

    /**
     * Scan for BLE devices
     */
    suspend fun scan(filters: List<ServiceFilter> = emptyList()): Unit = owned {
        plugins.interceptScan(ScanCall(filters)) { call ->
            engine.scan(call.filters)
        }
    }
    
    /**
     * Stop scanning
     */
    suspend fun stopScanning(): Unit = owned {
        engine.stopScanning()
    }
    
    /**
     * Clear discovered peripherals
     */
    fun clearPeripherals() {
        synchronous { engine.clearPeripherals() }
    }
    
    /**
     * Connect to a peripheral
     */
    suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean = false): Unit = owned {
        check(connectionStateStore.update(peripheral.uuid) { PeripheralConnectionState.Connecting }) {
            "Connection state storage capacity exceeded"
        }
        val result = plugins.interceptConnect(ConnectCall(peripheral, autoConnect)) { call ->
            engineResult {
                engine.connect(call.peripheral, call.autoConnect)
            }
        }
        currentCoroutineContext().ensureActive()
        result.exceptionOrNull()?.let { cause ->
            connectionStateStore.update(peripheral.uuid) {
                PeripheralConnectionState.Disconnected(DisconnectReason.ConnectFailed(cause))
            }
        }
    }
    
    /**
     * Disconnect from a peripheral
     */
    suspend fun disconnect(peripheral: BluetoothPeripheral): Unit = owned {
        // Diagnostic capacity must not prevent releasing a shared-engine native peer.
        connectionStateStore.update(peripheral.uuid) { PeripheralConnectionState.Disconnecting }
        plugins.interceptDisconnect(DisconnectCall(peripheral)) { call ->
            engineResult {
                engine.disconnect(call.peripheral)
            }
        }
    }
    
    /**
     * Get connection state
     */
    fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        return engine.connectionState(peripheral)
    }
    
    /**
     * Retrieve peripheral by identifier
     */
    fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        return engine.retrievePeripheral(identifier)
    }
    
    /**
     * Request connection priority
     */
    fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {
        synchronous { engine.requestConnectionPriority(peripheral, priority) }
    }
    
    /**
     * Discover services
     */
    suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid> = emptyList()): Unit = owned {
        engine.discoverServices(peripheral, serviceUUIDs)
    }
    
    /**
     * Discover characteristics
     */
    suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid> = emptyList()
    ): Unit = owned {
        engine.discoverCharacteristics(peripheral, service, characteristicUUIDs)
    }
    
    /**
     * Read a characteristic value.
     *
     * Suspends until the platform has actually delivered the value (ADR 0014) and returns it
     * directly - do not rely on [BluetoothCharacteristic.value] immediately after this call
     * returns, use [CharacteristicReadResult.Success.value] instead.
     */
    suspend fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic
    ): CharacteristicReadResult = owned {
        val result = plugins.interceptRead(ReadCall(peripheral, characteristic)) { call ->
            engineResult {
                engine.readCharacteristic(call.peripheral, call.characteristic)
            }
        }
        return@owned result.fold(
            onSuccess = { CharacteristicReadResult.Success(it) },
            onFailure = { CharacteristicReadResult.Failed(it) }
        )
    }
    
    /**
     * Write characteristic (string)
     */
    suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int? = null
    ): Unit = owned {
        writeCharacteristic(peripheral, characteristic, value.encodeToByteArray(), writeType)
    }
    
    /**
     * Write characteristic (bytes)
     */
    suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int? = null
    ): Unit = owned {
        plugins.interceptWrite(WriteCall(peripheral, characteristic, value, writeType)) { call ->
            engineResult {
                engine.writeCharacteristic(call.peripheral, call.characteristic, call.value, call.writeType)
            }
        }
    }

    suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: CharacteristicWriteType,
    ): CharacteristicWriteResult =
        owned {
            try {
                plugins.interceptCentralWrite(
                    CentralWriteCall(peripheral, characteristic, value, writeType)
                ) { call ->
                    engine.writeCharacteristic(
                        call.peripheral,
                        call.characteristic,
                        call.value,
                        call.writeType,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                CharacteristicWriteResult.Failed(failure)
            }
        }

    suspend fun setNotificationSubscription(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        enabled: Boolean,
    ): NotificationSubscriptionResult =
        owned {
            try {
                engine.setNotificationSubscription(peripheral, characteristic, enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                NotificationSubscriptionResult.Failed(failure)
            }
        }

    fun maximumWriteValueLength(
        peripheral: BluetoothPeripheral,
        writeType: CharacteristicWriteType,
    ): Int? = engine.maximumWriteValueLength(peripheral, writeType)
    
    /**
     * Enable/disable notifications
     */
    suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    ): Unit = owned {
        engine.notifyCharacteristic(peripheral, characteristic, notify)
    }
    
    /**
     * Enable/disable indications
     */
    suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ): Unit = owned {
        engine.indicateCharacteristic(peripheral, characteristic, indicate)
    }
    
    /**
     * Read descriptor
     */
    suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ): Unit = owned {
        engine.readDescriptor(peripheral, characteristic, descriptor)
    }
    
    /**
     * Write descriptor
     */
    suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ): Unit = owned {
        engine.writeDescriptor(peripheral, descriptor, value)
    }
    
    /**
     * Change MTU
     */
    suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int): Unit = owned {
        engine.changeMTU(peripheral, mtuSize)
    }
    
    /**
     * Refresh GATT cache
     */
    fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean {
        return synchronous { engine.refreshGattCache(peripheral) }
    }
    
    /**
     * Open an L2CAP connection-oriented channel and return the connected socket.
     */
    suspend fun openL2capChannel(
        peripheral: BluetoothPeripheral,
        psm: Int,
        secure: Boolean = false
    ): BluetoothSocket = owned {
        return@owned engine.openL2capChannel(peripheral, psm, secure)
    }
    
    /**
     * Create bond
     */
    suspend fun createBond(peripheral: BluetoothPeripheral): Unit = owned {
        engine.createBond(peripheral)
    }
    
    /**
     * Remove bond
     */
    suspend fun removeBond(peripheral: BluetoothPeripheral): Unit = owned {
        engine.removeBond(peripheral)
    }
}

/**
 * Configuration class for BlueFalcon DSL
 */
class BlueFalconConfig {
    lateinit var engine: BlueFalconEngine
    var ownsEngine: Boolean = false
    internal val pluginConfigs = mutableListOf<Pair<BlueFalconPlugin, PluginConfig.() -> Unit>>()
    
    /**
     * Install a plugin
     */
    fun <T : BlueFalconPlugin> install(plugin: T, configure: PluginConfig.() -> Unit = {}) {
        pluginConfigs.add(plugin to configure)
    }
}

/**
 * DSL function for creating BlueFalcon with configuration
 */
fun BlueFalcon(block: BlueFalconConfig.() -> Unit): BlueFalcon {
    val config = BlueFalconConfig().apply(block)
    val client = BlueFalcon(config.engine, config.ownsEngine)
    
    // Install all configured plugins (PluginRegistry.install invokes plugin.install(client, ...) internally)
    config.pluginConfigs.forEach { (plugin, configure) ->
        client.plugins.install(plugin, configure)
    }
    
    return client
}
