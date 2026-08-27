package dev.bluefalcon.core

import dev.bluefalcon.core.plugin.*
import kotlinx.coroutines.CancellationException
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
 * Main Blue Falcon client that wraps an engine and provides plugin support
 */
class BlueFalcon(
    val engine: BlueFalconEngine
) : BlueFalconClient {

    /**
     * Plugin registry for managing installed plugins
     */
    val plugins: PluginRegistry = PluginRegistry()

    /**
     * Backing store for the structured per-peripheral connection state machine (ADR 0008),
     * keyed by [BluetoothPeripheral.uuid].
     */
    private val _connectionStates = MutableStateFlow<Map<String, PeripheralConnectionState>>(emptyMap())

    init {
        engine.scope.launch {
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

        engine.scope.launch {
            engine.connectionStateUpdates.collect { update ->
                val uuid = update.peripheral.uuid
                when (update.state) {
                    BluetoothPeripheralState.Connected -> {
                        _connectionStates.update { it + (uuid to PeripheralConnectionState.Connected) }
                    }
                    BluetoothPeripheralState.Disconnected -> {
                        val previous = _connectionStates.value[uuid]
                        val reason = when (previous) {
                            is PeripheralConnectionState.Disconnecting -> DisconnectReason.UserInitiated
                            is PeripheralConnectionState.Connecting -> DisconnectReason.ConnectFailed(
                                BluetoothUnknownException()
                            )
                            is PeripheralConnectionState.Connected,
                            is PeripheralConnectionState.Ready -> DisconnectReason.Unexpected
                            else -> null
                        }
                        _connectionStates.update {
                            it + (uuid to PeripheralConnectionState.Disconnected(reason))
                        }
                    }
                    BluetoothPeripheralState.Connecting -> {
                        _connectionStates.update { it + (uuid to PeripheralConnectionState.Connecting) }
                    }
                    BluetoothPeripheralState.Disconnecting -> {
                        _connectionStates.update { it + (uuid to PeripheralConnectionState.Disconnecting) }
                    }
                    BluetoothPeripheralState.Unknown -> Unit
                }
            }
        }

        engine.scope.launch {
            engine.serviceDiscoveryUpdates.collect { update ->
                if (update.phase != ServiceDiscoveryPhase.ServicesDiscovered) return@collect
                val uuid = update.peripheral.uuid
                _connectionStates.update { current ->
                    if (current[uuid] == PeripheralConnectionState.Connected) {
                        current + (uuid to PeripheralConnectionState.Ready)
                    } else {
                        current
                    }
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
     * Derived from [connectionStateUpdates] and [serviceDiscoveryUpdates]. Prefer
     * [connectionStateFlow] or [peripheralState] for working with a single peripheral.
     */
    val connectionStates: StateFlow<Map<String, PeripheralConnectionState>> = _connectionStates.asStateFlow()

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
    fun connectionStateFlow(peripheral: BluetoothPeripheral): StateFlow<PeripheralConnectionState> =
        _connectionStates
            .map { it[peripheral.uuid] ?: PeripheralConnectionState.Disconnected() }
            .stateIn(engine.scope, SharingStarted.Eagerly, peripheralState(peripheral))
    
    /**
     * Scan for BLE devices
     */
    suspend fun scan(filters: List<ServiceFilter> = emptyList()) {
        plugins.interceptScan(ScanCall(filters)) { call ->
            engine.scan(call.filters)
        }
    }
    
    /**
     * Stop scanning
     */
    suspend fun stopScanning() {
        engine.stopScanning()
    }
    
    /**
     * Clear discovered peripherals
     */
    fun clearPeripherals() {
        engine.clearPeripherals()
    }
    
    /**
     * Connect to a peripheral
     */
    suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean = false) {
        _connectionStates.update {
            it + (peripheral.uuid to PeripheralConnectionState.Connecting)
        }
        val result = plugins.interceptConnect(ConnectCall(peripheral, autoConnect)) { call ->
            runCatching {
                engine.connect(call.peripheral, call.autoConnect)
            }
        }
        result.exceptionOrNull()?.let { cause ->
            _connectionStates.update {
                it + (peripheral.uuid to PeripheralConnectionState.Disconnected(DisconnectReason.ConnectFailed(cause)))
            }
        }
    }
    
    /**
     * Disconnect from a peripheral
     */
    suspend fun disconnect(peripheral: BluetoothPeripheral) {
        _connectionStates.update {
            it + (peripheral.uuid to PeripheralConnectionState.Disconnecting)
        }
        plugins.interceptDisconnect(DisconnectCall(peripheral)) { call ->
            runCatching {
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
        engine.requestConnectionPriority(peripheral, priority)
    }
    
    /**
     * Discover services
     */
    suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid> = emptyList()) {
        engine.discoverServices(peripheral, serviceUUIDs)
    }
    
    /**
     * Discover characteristics
     */
    suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid> = emptyList()
    ) {
        engine.discoverCharacteristics(peripheral, service, characteristicUUIDs)
    }
    
    /**
     * Read characteristic
     */
    suspend fun readCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic) {
        plugins.interceptRead(ReadCall(peripheral, characteristic)) { call ->
            runCatching {
                engine.readCharacteristic(call.peripheral, call.characteristic)
                call.characteristic.value
            }
        }
    }
    
    /**
     * Write characteristic (string)
     */
    suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int? = null
    ) {
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
    ) {
        plugins.interceptWrite(WriteCall(peripheral, characteristic, value, writeType)) { call ->
            runCatching {
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

    suspend fun setNotificationSubscription(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        enabled: Boolean,
    ): NotificationSubscriptionResult =
        try {
            engine.setNotificationSubscription(peripheral, characteristic, enabled)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            NotificationSubscriptionResult.Failed(failure)
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
    ) {
        engine.notifyCharacteristic(peripheral, characteristic, notify)
    }
    
    /**
     * Enable/disable indications
     */
    suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        engine.indicateCharacteristic(peripheral, characteristic, indicate)
    }
    
    /**
     * Read descriptor
     */
    suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ) {
        engine.readDescriptor(peripheral, characteristic, descriptor)
    }
    
    /**
     * Write descriptor
     */
    suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        engine.writeDescriptor(peripheral, descriptor, value)
    }
    
    /**
     * Change MTU
     */
    suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {
        engine.changeMTU(peripheral, mtuSize)
    }
    
    /**
     * Refresh GATT cache
     */
    fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean {
        return engine.refreshGattCache(peripheral)
    }
    
    /**
     * Open an L2CAP connection-oriented channel and return the connected socket.
     */
    suspend fun openL2capChannel(
        peripheral: BluetoothPeripheral,
        psm: Int,
        secure: Boolean = false
    ): BluetoothSocket {
        return engine.openL2capChannel(peripheral, psm, secure)
    }
    
    /**
     * Create bond
     */
    suspend fun createBond(peripheral: BluetoothPeripheral) {
        engine.createBond(peripheral)
    }
    
    /**
     * Remove bond
     */
    suspend fun removeBond(peripheral: BluetoothPeripheral) {
        engine.removeBond(peripheral)
    }
}

/**
 * Configuration class for BlueFalcon DSL
 */
class BlueFalconConfig {
    lateinit var engine: BlueFalconEngine
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
    val client = BlueFalcon(config.engine)
    
    // Install all configured plugins
    config.pluginConfigs.forEach { (plugin, configure) ->
        plugin.install(client, PluginConfig().apply(configure))
        client.plugins.install(plugin, configure)
    }
    
    return client
}
