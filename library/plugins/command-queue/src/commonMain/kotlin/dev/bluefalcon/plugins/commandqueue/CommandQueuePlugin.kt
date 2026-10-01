package dev.bluefalcon.plugins.commandqueue

import dev.bluefalcon.core.*
import dev.bluefalcon.core.plugin.BlueFalconClient
import dev.bluefalcon.core.plugin.BlueFalconPlugin
import dev.bluefalcon.core.plugin.PluginConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Operations supported by [CommandQueuePlugin]. */
enum class CommandQueueOperation {
    Write,
    Read,
    DiscoverServices,
    DiscoverCharacteristics,
    ChangeMtu,
    SetSubscription,
}

/** The current durable state of a command accepted by [CommandQueuePlugin]. */
enum class CommandQueuePhase {
    Queued,
    Sending,
    Backpressured,
}

/** Metadata exposed for an outstanding command. Payload bytes are intentionally omitted. */
data class QueuedCommandStatus(
    val id: Long,
    val peripheralUuid: String,
    val operation: CommandQueueOperation,
    val detail: String,
    val accountedBytes: Int,
    val phase: CommandQueuePhase,
)

/** Durable snapshot for queue monitoring. */
data class CommandQueueSnapshot(
    val commands: List<QueuedCommandStatus> = emptyList(),
    val accountedBytes: Int = 0,
) {
    val queuedCount: Int
        get() = commands.count { it.phase != CommandQueuePhase.Sending }

    val inFlightCount: Int
        get() = commands.count { it.phase == CommandQueuePhase.Sending }
}

/** Terminal outcome of a queued central command. */
sealed interface CommandQueueResult {
    data object Sent : CommandQueueResult
    data class Read(val value: ByteArray?) : CommandQueueResult
    data object ServicesDiscovered : CommandQueueResult
    data class CharacteristicsDiscovered(val serviceUuid: Uuid) : CommandQueueResult
    data class MtuChangeRequested(val requestedMtu: Int) : CommandQueueResult
    data class SubscriptionUpdated(val enabled: Boolean) : CommandQueueResult
    data object QueueFull : CommandQueueResult
    data object Backpressured : CommandQueueResult
    data class PayloadTooLarge(val maximumLength: Int) : CommandQueueResult
    data object TimedOut : CommandQueueResult
    data object Disconnected : CommandQueueResult
    data object Unsupported : CommandQueueResult
    data object Closed : CommandQueueResult
    data object Cancelled : CommandQueueResult
    data class Failed(val cause: Throwable?) : CommandQueueResult
}

/** Best-effort transition events. Use [CommandQueuePlugin.state] for durable current state. */
sealed interface CommandQueueEvent {
    data class StatusChanged(val command: QueuedCommandStatus) : CommandQueueEvent
    data class Completed(
        val commandId: Long,
        val peripheralUuid: String,
        val operation: CommandQueueOperation,
        val result: CommandQueueResult,
    ) : CommandQueueEvent
}

/**
 * Bounded, observable, per-peripheral FIFO for central GATT commands.
 *
 * A peripheral has at most one queue command in progress, while different peripherals can progress
 * concurrently. Writes handle typed backpressure. Reads and subscriptions use their typed terminal
 * results. Discovery waits for the matching discovery event. MTU APIs do not expose a portable
 * negotiated result, so a successful MTU command reports [CommandQueueResult.MtuChangeRequested].
 *
 * The queue does not fragment payloads, persist commands, reconnect devices, or retry terminal
 * failures. Call [close] when the owning client is no longer used.
 */
class CommandQueuePlugin private constructor(
    private val config: Config,
) : BlueFalconPlugin {

    class Config : PluginConfig() {
        var maxPendingItemsPerPeripheral: Int = 64
        var maxPendingBytes: Int = 64 * 1024
        var operationTimeoutMillis: Long = 30_000
    }

    private val mutex = Mutex()
    private val queues = linkedMapOf<String, ArrayDeque<QueuedCommand>>()
    private val workers = mutableMapOf<String, Job>()
    private val _state = MutableStateFlow(CommandQueueSnapshot())
    private val _events = MutableSharedFlow<CommandQueueEvent>(extraBufferCapacity = 64)
    private val discoveredCharacteristics = MutableStateFlow<Set<DiscoveryKey>>(emptySet())

    val state: StateFlow<CommandQueueSnapshot> = _state.asStateFlow()
    val events: SharedFlow<CommandQueueEvent> = _events.asSharedFlow()

    private var blueFalcon: BlueFalcon? = null
    private var pluginJob: Job? = null
    private var scope: CoroutineScope? = null
    private var closed = false
    private var nextCommandId = 1L
    private var accountedBytes = 0

    override fun install(
        client: BlueFalconClient,
        @Suppress("UNUSED_PARAMETER") config: PluginConfig,
    ) {
        check(blueFalcon == null) { "CommandQueuePlugin is already installed" }
        val installedClient = client as? BlueFalcon
            ?: error("CommandQueuePlugin requires a BlueFalcon client")
        val parentContext = installedClient.engine.scope.coroutineContext
        val job = SupervisorJob(parentContext[Job])
        val installedScope = CoroutineScope(parentContext + job)
        blueFalcon = installedClient
        pluginJob = job
        scope = installedScope

        installedScope.launch(start = CoroutineStart.UNDISPATCHED) {
            installedClient.connectionStateUpdates.collect { update ->
                if (update.state == BluetoothPeripheralState.Disconnected) {
                    discoveredCharacteristics.update { discovered ->
                        discovered.filterTo(mutableSetOf()) {
                            it.peripheralUuid != update.peripheral.uuid
                        }
                    }
                    disconnectPeripheral(update.peripheral.uuid)
                }
            }
        }
        installedScope.launch(start = CoroutineStart.UNDISPATCHED) {
            installedClient.serviceDiscoveryUpdates.collect { update ->
                val service = update.service
                if (
                    update.phase == ServiceDiscoveryPhase.CharacteristicsDiscovered &&
                    service != null
                ) {
                    discoveredCharacteristics.update {
                        it + DiscoveryKey(update.peripheral.uuid, service.uuid)
                    }
                }
            }
        }
    }

    suspend fun send(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: CharacteristicWriteType = CharacteristicWriteType.WithResponse,
    ): CommandQueueResult {
        val client = installedClient()
        client.maximumWriteValueLength(peripheral, writeType)?.let { maximum ->
            if (value.size > maximum) {
                return CommandQueueResult.PayloadTooLarge(maximum)
            }
        }
        return enqueue(peripheral, QueuedOperation.Write(characteristic, value, writeType))
    }

    suspend fun read(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
    ): CommandQueueResult = enqueue(peripheral, QueuedOperation.Read(characteristic))

    suspend fun discoverServices(
        peripheral: BluetoothPeripheral,
        serviceUuids: List<Uuid> = emptyList(),
    ): CommandQueueResult = enqueue(
        peripheral,
        QueuedOperation.DiscoverServices(serviceUuids),
    )

    suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUuids: List<Uuid> = emptyList(),
    ): CommandQueueResult = enqueue(
        peripheral,
        QueuedOperation.DiscoverCharacteristics(service, characteristicUuids),
    )

    suspend fun changeMtu(
        peripheral: BluetoothPeripheral,
        mtuSize: Int,
    ): CommandQueueResult {
        if (mtuSize <= 0) {
            return CommandQueueResult.Failed(
                IllegalArgumentException("mtuSize must be positive"),
            )
        }
        return enqueue(peripheral, QueuedOperation.ChangeMtu(mtuSize))
    }

    suspend fun setNotificationSubscription(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        enabled: Boolean,
    ): CommandQueueResult = enqueue(
        peripheral,
        QueuedOperation.SetSubscription(characteristic, enabled),
    )

    /** Stops workers and completes every outstanding command with [CommandQueueResult.Closed]. */
    suspend fun close() {
        val commands = mutex.withLock {
            if (closed) return
            closed = true
            drainAllLocked().also { publishStateLocked() }
        }
        commands.forEach { complete(it, CommandQueueResult.Closed) }
        pluginJob?.cancelAndJoin()
        pluginJob = null
        scope = null
    }

    private suspend fun enqueue(
        peripheral: BluetoothPeripheral,
        operation: QueuedOperation,
    ): CommandQueueResult {
        installedClient()
        val command = QueuedCommand(nextId(), peripheral, operation)
        var workerToStart: Job? = null
        val decision = mutex.withLock {
            if (closed) return@withLock EnqueueDecision.Closed
            val queue = queues.getOrPut(peripheral.uuid) { ArrayDeque() }
            if (
                queue.size >= config.maxPendingItemsPerPeripheral ||
                command.accountedByteCount > config.maxPendingBytes - accountedBytes
            ) {
                if (queue.isEmpty()) queues.remove(peripheral.uuid)
                return@withLock EnqueueDecision.Full
            }

            queue.addLast(command)
            accountedBytes += command.accountedByteCount
            publishStateLocked()
            _events.tryEmit(CommandQueueEvent.StatusChanged(command.status()))

            if (workers[peripheral.uuid]?.isActive != true) {
                val installedScope = checkNotNull(scope)
                workerToStart = installedScope.launch(start = CoroutineStart.LAZY) {
                    drainPeripheral(peripheral.uuid)
                }
                workers[peripheral.uuid] = checkNotNull(workerToStart)
            }
            EnqueueDecision.Accepted
        }

        when (decision) {
            EnqueueDecision.Closed -> return CommandQueueResult.Closed
            EnqueueDecision.Full -> return CommandQueueResult.QueueFull
            EnqueueDecision.Accepted -> Unit
        }
        workerToStart?.start()

        return try {
            command.completion.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { cancelIfPending(command) }
            throw cancelled
        }
    }

    private suspend fun drainPeripheral(peripheralUuid: String) {
        val workerJob = kotlin.coroutines.coroutineContext[Job]
        try {
            while (true) {
                val command = mutex.withLock {
                    val head = queues[peripheralUuid]?.firstOrNull()
                    if (head == null) {
                        removeCurrentWorkerLocked(peripheralUuid, workerJob)
                        return
                    }
                    head.submitting = true
                    head.phase = CommandQueuePhase.Sending
                    publishStateLocked()
                    _events.tryEmit(CommandQueueEvent.StatusChanged(head.status()))
                    head
                }

                when (val execution = execute(command)) {
                    is ExecutionResult.Terminal -> {
                        if (execution.result == CommandQueueResult.Disconnected) {
                            completeDisconnected(peripheralUuid)
                            return
                        }
                        finishHead(command, execution.result)
                    }
                    is ExecutionResult.RetryWrite -> {
                        if (!markBackpressured(command)) continue
                        awaitWriteReadiness(execution.key)?.let { terminal ->
                            finishHead(command, terminal)
                        }
                    }
                }
            }
        } finally {
            mutex.withLock { removeCurrentWorkerLocked(peripheralUuid, workerJob) }
        }
    }

    private suspend fun execute(command: QueuedCommand): ExecutionResult = try {
        val client = installedClient()
        val readinessKey = CharacteristicWriteKey(
            command.peripheral.uuid,
            (command.operation as? QueuedOperation.Write)?.writeType
                ?: CharacteristicWriteType.WithResponse,
        )
        awaitGattAvailability(client, readinessKey)?.let { terminal ->
            return ExecutionResult.Terminal(terminal)
        }
        when (val operation = command.operation) {
            is QueuedOperation.Write -> executeWrite(client, command.peripheral, operation)
            is QueuedOperation.Read -> ExecutionResult.Terminal(
                client.readCharacteristic(command.peripheral, operation.characteristic)
                    .toQueueResult(),
            )
            is QueuedOperation.DiscoverServices -> ExecutionResult.Terminal(
                executeServiceDiscovery(client, command.peripheral, operation),
            )
            is QueuedOperation.DiscoverCharacteristics -> ExecutionResult.Terminal(
                executeCharacteristicDiscovery(client, command.peripheral, operation),
            )
            is QueuedOperation.ChangeMtu -> ExecutionResult.Terminal(
                executeMtuChange(client, command.peripheral, operation.mtuSize),
            )
            is QueuedOperation.SetSubscription -> ExecutionResult.Terminal(
                client.setNotificationSubscription(
                    command.peripheral,
                    operation.characteristic,
                    operation.enabled,
                ).toQueueResult(),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        ExecutionResult.Terminal(CommandQueueResult.Failed(failure))
    }

    private suspend fun executeWrite(
        client: BlueFalcon,
        peripheral: BluetoothPeripheral,
        operation: QueuedOperation.Write,
    ): ExecutionResult {
        client.maximumWriteValueLength(peripheral, operation.writeType)?.let { maximum ->
            if (operation.value.size > maximum) {
                return ExecutionResult.Terminal(CommandQueueResult.PayloadTooLarge(maximum))
            }
        }
        return when (
            val result = client.writeCharacteristic(
                peripheral,
                operation.characteristic,
                operation.value.copyOf(),
                operation.writeType,
            )
        ) {
            CharacteristicWriteResult.Backpressured -> ExecutionResult.RetryWrite(
                CharacteristicWriteKey(peripheral.uuid, operation.writeType),
            )
            else -> ExecutionResult.Terminal(result.toQueueResult())
        }
    }

    private suspend fun executeServiceDiscovery(
        client: BlueFalcon,
        peripheral: BluetoothPeripheral,
        operation: QueuedOperation.DiscoverServices,
    ): CommandQueueResult {
        val completed = withTimeoutOrNull(config.operationTimeoutMillis) {
            coroutineScope {
                val completion = async(start = CoroutineStart.UNDISPATCHED) {
                    client.serviceDiscoveryUpdates.first { update ->
                        update.peripheral.uuid == peripheral.uuid &&
                            update.phase == ServiceDiscoveryPhase.ServicesDiscovered
                    }
                }
                client.discoverServices(peripheral, operation.serviceUuids)
                completion.await()
            }
        }
        return if (completed == null) {
            CommandQueueResult.TimedOut
        } else {
            CommandQueueResult.ServicesDiscovered
        }
    }

    private suspend fun executeCharacteristicDiscovery(
        client: BlueFalcon,
        peripheral: BluetoothPeripheral,
        operation: QueuedOperation.DiscoverCharacteristics,
    ): CommandQueueResult {
        val key = DiscoveryKey(peripheral.uuid, operation.service.uuid)
        if (
            operation.service.characteristics.isNotEmpty() ||
            key in discoveredCharacteristics.value
        ) {
            return CommandQueueResult.CharacteristicsDiscovered(operation.service.uuid)
        }
        val completed = withTimeoutOrNull(config.operationTimeoutMillis) {
            coroutineScope {
                val completion = async(start = CoroutineStart.UNDISPATCHED) {
                    client.serviceDiscoveryUpdates.first { update ->
                        update.peripheral.uuid == peripheral.uuid &&
                            update.phase == ServiceDiscoveryPhase.CharacteristicsDiscovered &&
                            update.service?.uuid == operation.service.uuid
                    }
                }
                client.discoverCharacteristics(
                    peripheral,
                    operation.service,
                    operation.characteristicUuids,
                )
                completion.await()
            }
        }
        return if (completed == null) {
            CommandQueueResult.TimedOut
        } else {
            CommandQueueResult.CharacteristicsDiscovered(operation.service.uuid)
        }
    }

    private suspend fun executeMtuChange(
        client: BlueFalcon,
        peripheral: BluetoothPeripheral,
        mtuSize: Int,
    ): CommandQueueResult {
        client.changeMTU(peripheral, mtuSize)
        val key = CharacteristicWriteKey(
            peripheral.uuid,
            CharacteristicWriteType.WithResponse,
        )
        val capability = client.characteristicWriteCapabilities.value[key]
        if (capability != null && !capability.ready) {
            val settled = withTimeoutOrNull(config.operationTimeoutMillis) {
                client.characteristicWriteCapabilities.first { capabilities ->
                    capabilities[key]?.let { !it.supported || it.ready } ?: true
                }
            } ?: return CommandQueueResult.TimedOut
            val finalCapability = settled[key]
            if (finalCapability == null) return CommandQueueResult.Disconnected
            if (!finalCapability.supported) return CommandQueueResult.Unsupported
        }
        return CommandQueueResult.MtuChangeRequested(mtuSize)
    }

    private suspend fun awaitGattAvailability(
        client: BlueFalcon,
        key: CharacteristicWriteKey,
    ): CommandQueueResult? {
        val initial = client.characteristicWriteCapabilities.value[key]
        if (initial == null || !initial.supported || initial.ready) return null
        val capabilities = withTimeoutOrNull(config.operationTimeoutMillis) {
            client.characteristicWriteCapabilities.first { state ->
                state[key]?.let { !it.supported || it.ready } ?: true
            }
        } ?: return CommandQueueResult.TimedOut
        val capability = capabilities[key] ?: return CommandQueueResult.Disconnected
        return if (capability.supported) null else CommandQueueResult.Unsupported
    }

    private suspend fun markBackpressured(command: QueuedCommand): Boolean = mutex.withLock {
        val current = queues[command.peripheral.uuid]?.firstOrNull()
        if (current !== command) return@withLock false
        command.submitting = false
        command.phase = CommandQueuePhase.Backpressured
        publishStateLocked()
        _events.tryEmit(CommandQueueEvent.StatusChanged(command.status()))
        true
    }

    /** Returns null when ready, or a terminal result when retrying is impossible. */
    private suspend fun awaitWriteReadiness(
        key: CharacteristicWriteKey,
    ): CommandQueueResult? {
        val client = installedClient()
        if (client.characteristicWriteCapabilities.value[key] == null) {
            return CommandQueueResult.Backpressured
        }
        val capabilities = withTimeoutOrNull(config.operationTimeoutMillis) {
            client.characteristicWriteCapabilities.first { state ->
                state[key]?.let { !it.supported || it.ready } ?: true
            }
        } ?: return CommandQueueResult.TimedOut
        val capability = capabilities[key] ?: return CommandQueueResult.Disconnected
        return if (capability.supported) null else CommandQueueResult.Unsupported
    }

    private suspend fun finishHead(command: QueuedCommand, result: CommandQueueResult) {
        val removed = mutex.withLock {
            val queue = queues[command.peripheral.uuid] ?: return@withLock false
            if (queue.firstOrNull() !== command) return@withLock false
            queue.removeFirst()
            accountedBytes -= command.accountedByteCount
            if (queue.isEmpty()) queues.remove(command.peripheral.uuid)
            publishStateLocked()
            true
        }
        if (removed) complete(command, result)
    }

    private suspend fun cancelIfPending(command: QueuedCommand) {
        var workerToStart: Job? = null
        val removed = mutex.withLock {
            if (command.submitting) return@withLock false
            val peripheralUuid = command.peripheral.uuid
            val wasHead = queues[peripheralUuid]?.firstOrNull() === command
            removeCommandLocked(command).also { wasRemoved ->
                if (wasRemoved) {
                    if (wasHead) {
                        workers.remove(peripheralUuid)?.cancel()
                        if (queues[peripheralUuid]?.isNotEmpty() == true) {
                            val installedScope = checkNotNull(scope)
                            workerToStart = installedScope.launch(start = CoroutineStart.LAZY) {
                                drainPeripheral(peripheralUuid)
                            }
                            workers[peripheralUuid] = checkNotNull(workerToStart)
                        }
                    }
                    publishStateLocked()
                }
            }
        }
        workerToStart?.start()
        if (removed) complete(command, CommandQueueResult.Cancelled)
    }

    private suspend fun completeDisconnected(peripheralUuid: String) {
        val commands = mutex.withLock {
            drainPeripheralLocked(peripheralUuid).also { publishStateLocked() }
        }
        commands.forEach { complete(it, CommandQueueResult.Disconnected) }
    }

    private suspend fun disconnectPeripheral(peripheralUuid: String) {
        val commands = mutex.withLock {
            workers.remove(peripheralUuid)?.cancel()
            drainPeripheralLocked(peripheralUuid).also { publishStateLocked() }
        }
        commands.forEach { complete(it, CommandQueueResult.Disconnected) }
    }

    private fun removeCommandLocked(command: QueuedCommand): Boolean {
        val queue = queues[command.peripheral.uuid] ?: return false
        if (!queue.remove(command)) return false
        accountedBytes -= command.accountedByteCount
        if (queue.isEmpty()) queues.remove(command.peripheral.uuid)
        return true
    }

    private fun drainPeripheralLocked(peripheralUuid: String): List<QueuedCommand> {
        val queue = queues.remove(peripheralUuid) ?: return emptyList()
        return buildList {
            while (queue.isNotEmpty()) {
                queue.removeFirst().also { command ->
                    accountedBytes -= command.accountedByteCount
                    add(command)
                }
            }
        }
    }

    private fun drainAllLocked(): List<QueuedCommand> = buildList {
        queues.keys.toList().forEach { addAll(drainPeripheralLocked(it)) }
        workers.clear()
    }

    private fun removeCurrentWorkerLocked(peripheralUuid: String, workerJob: Job?) {
        if (workers[peripheralUuid] === workerJob) workers.remove(peripheralUuid)
    }

    private fun publishStateLocked() {
        _state.value = CommandQueueSnapshot(
            commands = queues.values.flatMap { queue -> queue.map(QueuedCommand::status) },
            accountedBytes = accountedBytes,
        )
    }

    private fun complete(command: QueuedCommand, result: CommandQueueResult) {
        command.completion.complete(result)
        _events.tryEmit(
            CommandQueueEvent.Completed(
                commandId = command.id,
                peripheralUuid = command.peripheral.uuid,
                operation = command.operation.kind,
                result = result,
            ),
        )
    }

    private fun installedClient(): BlueFalcon =
        blueFalcon ?: error("CommandQueuePlugin must be installed before use")

    private suspend fun nextId(): Long = mutex.withLock { nextCommandId++ }

    private fun CharacteristicWriteResult.toQueueResult(): CommandQueueResult = when (this) {
        CharacteristicWriteResult.Sent -> CommandQueueResult.Sent
        CharacteristicWriteResult.Backpressured -> CommandQueueResult.Backpressured
        is CharacteristicWriteResult.PayloadTooLarge ->
            CommandQueueResult.PayloadTooLarge(maximumLength)
        CharacteristicWriteResult.Disconnected -> CommandQueueResult.Disconnected
        CharacteristicWriteResult.Unsupported -> CommandQueueResult.Unsupported
        is CharacteristicWriteResult.Failed -> CommandQueueResult.Failed(cause)
    }

    private fun CharacteristicReadResult.toQueueResult(): CommandQueueResult = when (this) {
        is CharacteristicReadResult.Success -> CommandQueueResult.Read(value?.copyOf())
        CharacteristicReadResult.Disconnected -> CommandQueueResult.Disconnected
        CharacteristicReadResult.Unsupported -> CommandQueueResult.Unsupported
        is CharacteristicReadResult.Failed -> CommandQueueResult.Failed(cause)
    }

    private fun NotificationSubscriptionResult.toQueueResult(): CommandQueueResult = when (this) {
        is NotificationSubscriptionResult.Updated -> CommandQueueResult.SubscriptionUpdated(enabled)
        NotificationSubscriptionResult.Disconnected -> CommandQueueResult.Disconnected
        NotificationSubscriptionResult.Unsupported -> CommandQueueResult.Unsupported
        is NotificationSubscriptionResult.Failed -> CommandQueueResult.Failed(cause)
    }

    companion object {
        fun create(configure: Config.() -> Unit = {}): CommandQueuePlugin {
            val config = Config().apply(configure)
            require(config.maxPendingItemsPerPeripheral > 0) {
                "maxPendingItemsPerPeripheral must be positive"
            }
            require(config.maxPendingBytes > 0) { "maxPendingBytes must be positive" }
            require(config.operationTimeoutMillis > 0) {
                "operationTimeoutMillis must be positive"
            }
            return CommandQueuePlugin(config)
        }
    }
}

private enum class EnqueueDecision { Accepted, Full, Closed }

private data class DiscoveryKey(
    val peripheralUuid: String,
    val serviceUuid: Uuid,
)

private sealed interface ExecutionResult {
    data class Terminal(val result: CommandQueueResult) : ExecutionResult
    data class RetryWrite(val key: CharacteristicWriteKey) : ExecutionResult
}

private sealed class QueuedOperation(
    val kind: CommandQueueOperation,
    val detail: String,
    val accountedByteCount: Int = 1,
) {
    class Write(
        val characteristic: BluetoothCharacteristic,
        value: ByteArray,
        val writeType: CharacteristicWriteType,
    ) : QueuedOperation(
        CommandQueueOperation.Write,
        "${characteristic.uuid} ($writeType, ${value.size} bytes)",
        maxOf(value.size, 1),
    ) {
        val value = value.copyOf()
    }

    class Read(
        val characteristic: BluetoothCharacteristic,
    ) : QueuedOperation(CommandQueueOperation.Read, characteristic.uuid.toString())

    class DiscoverServices(
        serviceUuids: List<Uuid>,
    ) : QueuedOperation(
        CommandQueueOperation.DiscoverServices,
        if (serviceUuids.isEmpty()) "all services" else "${serviceUuids.size} filtered services",
    ) {
        val serviceUuids = serviceUuids.toList()
    }

    class DiscoverCharacteristics(
        val service: BluetoothService,
        characteristicUuids: List<Uuid>,
    ) : QueuedOperation(
        CommandQueueOperation.DiscoverCharacteristics,
        service.uuid.toString(),
    ) {
        val characteristicUuids = characteristicUuids.toList()
    }

    class ChangeMtu(
        val mtuSize: Int,
    ) : QueuedOperation(CommandQueueOperation.ChangeMtu, mtuSize.toString())

    class SetSubscription(
        val characteristic: BluetoothCharacteristic,
        val enabled: Boolean,
    ) : QueuedOperation(
        CommandQueueOperation.SetSubscription,
        "${characteristic.uuid} enabled=$enabled",
    )
}

private class QueuedCommand(
    val id: Long,
    val peripheral: BluetoothPeripheral,
    val operation: QueuedOperation,
) {
    val accountedByteCount = operation.accountedByteCount
    val completion = CompletableDeferred<CommandQueueResult>()
    var phase = CommandQueuePhase.Queued
    var submitting = false

    fun status() = QueuedCommandStatus(
        id = id,
        peripheralUuid = peripheral.uuid,
        operation = operation.kind,
        detail = operation.detail,
        accountedBytes = accountedByteCount,
        phase = phase,
    )
}
