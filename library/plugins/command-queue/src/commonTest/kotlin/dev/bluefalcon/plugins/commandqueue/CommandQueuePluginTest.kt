package dev.bluefalcon.plugins.commandqueue

import dev.bluefalcon.core.AdapterSelectionResult
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BlueFalconEngine
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.BluetoothSocket
import dev.bluefalcon.core.CentralCapabilities
import dev.bluefalcon.core.CharacteristicNotification
import dev.bluefalcon.core.CharacteristicWriteCapability
import dev.bluefalcon.core.CharacteristicWriteKey
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import dev.bluefalcon.core.ConnectionPriority
import dev.bluefalcon.core.ConnectionStateUpdate
import dev.bluefalcon.core.NotificationSubscriptionResult
import dev.bluefalcon.core.ServiceDiscoveryUpdate
import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.core.Uuid
import dev.bluefalcon.core.toUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CommandQueuePluginTest {

    @Test
    fun validatesQueueLimits() {
        assertFailsWith<IllegalArgumentException> {
            CommandQueuePlugin.create { maxPendingItemsPerPeripheral = 0 }
        }
        assertFailsWith<IllegalArgumentException> {
            CommandQueuePlugin.create { maxPendingBytes = 0 }
        }
        assertFailsWith<IllegalArgumentException> {
            CommandQueuePlugin.create { operationTimeoutMillis = 0 }
        }
    }

    @Test
    fun writesOnePeripheralInFifoOrderAndCopiesPayloads() = runTest {
        val engine = FakeEngine(backgroundScope)
        val queue = installedQueue(engine)
        val peripheral = FakePeripheral("one")
        val characteristic = FakeCharacteristic()
        val firstMayFinish = CompletableDeferred<Unit>()
        engine.onWrite = { _, _, value, _ ->
            engine.values += value.copyOf()
            if (engine.values.size == 1) firstMayFinish.await()
            CharacteristicWriteResult.Sent
        }
        val firstPayload = byteArrayOf(1)

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(peripheral, characteristic, firstPayload)
        }
        firstPayload[0] = 99
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(peripheral, characteristic, byteArrayOf(2))
        }
        runCurrent()

        assertEquals(1, engine.values.size)
        assertEquals(2, queue.state.value.commands.size)
        assertEquals(CommandQueuePhase.Sending, queue.state.value.commands[0].phase)
        assertEquals(CommandQueuePhase.Queued, queue.state.value.commands[1].phase)

        firstMayFinish.complete(Unit)
        runCurrent()

        assertEquals(CommandQueueResult.Sent, first.await())
        assertEquals(CommandQueueResult.Sent, second.await())
        assertContentEquals(byteArrayOf(1), engine.values[0])
        assertContentEquals(byteArrayOf(2), engine.values[1])
        assertTrue(queue.state.value.commands.isEmpty())
        queue.close()
    }

    @Test
    fun differentPeripheralsCanWriteConcurrently() = runTest {
        val engine = FakeEngine(backgroundScope)
        val queue = installedQueue(engine)
        val firstPeripheral = FakePeripheral("one")
        val secondPeripheral = FakePeripheral("two")
        val characteristic = FakeCharacteristic()
        val firstMayFinish = CompletableDeferred<Unit>()
        engine.onWrite = { peripheral, _, _, _ ->
            if (peripheral.uuid == firstPeripheral.uuid) firstMayFinish.await()
            CharacteristicWriteResult.Sent
        }

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(firstPeripheral, characteristic, byteArrayOf(1))
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(secondPeripheral, characteristic, byteArrayOf(2))
        }
        runCurrent()

        assertEquals(CommandQueueResult.Sent, second.await())
        assertFalse(first.isCompleted)
        firstMayFinish.complete(Unit)
        runCurrent()
        assertEquals(CommandQueueResult.Sent, first.await())
        queue.close()
    }

    @Test
    fun mixedGattCommandsShareOnePerPeripheralFifo() = runTest {
        val engine = FakeEngine(backgroundScope)
        val queue = installedQueue(engine)
        val peripheral = FakePeripheral("one")
        val characteristic = FakeCharacteristic()
        val service = FakeService()
        engine.readValue = byteArrayOf(7, 8)
        engine.subscriptionResult = NotificationSubscriptionResult.Updated(true)

        val write = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(peripheral, characteristic, byteArrayOf(1))
        }
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            queue.read(peripheral, characteristic)
        }
        val services = async(start = CoroutineStart.UNDISPATCHED) {
            queue.discoverServices(peripheral)
        }
        val characteristics = async(start = CoroutineStart.UNDISPATCHED) {
            queue.discoverCharacteristics(peripheral, service)
        }
        val mtu = async(start = CoroutineStart.UNDISPATCHED) {
            queue.changeMtu(peripheral, 247)
        }
        val subscription = async(start = CoroutineStart.UNDISPATCHED) {
            queue.setNotificationSubscription(peripheral, characteristic, enabled = true)
        }
        runCurrent()

        assertEquals(CommandQueueResult.Sent, write.await())
        assertContentEquals(
            byteArrayOf(7, 8),
            assertIs<CommandQueueResult.Read>(read.await()).value,
        )
        assertEquals(CommandQueueResult.ServicesDiscovered, services.await())
        assertEquals(
            CommandQueueResult.CharacteristicsDiscovered(service.uuid),
            characteristics.await(),
        )
        assertEquals(CommandQueueResult.MtuChangeRequested(247), mtu.await())
        assertEquals(CommandQueueResult.SubscriptionUpdated(true), subscription.await())
        assertEquals(
            listOf(
                "write",
                "read",
                "discover-services",
                "discover-characteristics",
                "change-mtu",
                "subscription",
            ),
            engine.operations,
        )
        queue.close()
    }

    @Test
    fun commandWaitsForKnownBusyGattGate() = runTest {
        val engine = FakeEngine(backgroundScope)
        val queue = installedQueue(engine)
        val peripheral = FakePeripheral("one")
        val key = CharacteristicWriteKey(peripheral.uuid, CharacteristicWriteType.WithResponse)
        engine.readValue = byteArrayOf(4)
        engine.capabilities.value = mapOf(
            key to CharacteristicWriteCapability(20, ready = false, supported = true),
        )

        val reading = async(start = CoroutineStart.UNDISPATCHED) {
            queue.read(peripheral, FakeCharacteristic())
        }
        runCurrent()

        assertFalse(reading.isCompleted)
        assertTrue(engine.operations.isEmpty())

        engine.capabilities.value = mapOf(
            key to CharacteristicWriteCapability(20, ready = true, supported = true),
        )
        runCurrent()

        assertContentEquals(
            byteArrayOf(4),
            assertIs<CommandQueueResult.Read>(reading.await()).value,
        )
        queue.close()
    }

    @Test
    fun discoveryWithoutCompletionEventTimesOut() = runTest {
        val engine = FakeEngine(backgroundScope).apply { emitDiscoveryUpdates = false }
        val queue = installedQueue(engine) { operationTimeoutMillis = 100 }

        val discovery = async(start = CoroutineStart.UNDISPATCHED) {
            queue.discoverServices(FakePeripheral("one"))
        }
        advanceUntilIdle()

        assertEquals(CommandQueueResult.TimedOut, discovery.await())
        queue.close()
    }

    @Test
    fun backpressuredWriteWaitsForDurableReadiness() = runTest {
        val engine = FakeEngine(backgroundScope)
        val queue = installedQueue(engine)
        val peripheral = FakePeripheral("one")
        val characteristic = FakeCharacteristic()
        val key = CharacteristicWriteKey(
            peripheral.uuid,
            CharacteristicWriteType.WithoutResponse,
        )
        engine.capabilities.value = mapOf(
            key to CharacteristicWriteCapability(20, ready = true, supported = true),
        )
        var attempts = 0
        engine.onWrite = { _, _, _, _ ->
            attempts++
            if (attempts == 1) {
                engine.capabilities.value = mapOf(
                    key to CharacteristicWriteCapability(20, ready = false, supported = true),
                )
                CharacteristicWriteResult.Backpressured
            } else {
                CharacteristicWriteResult.Sent
            }
        }

        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(
                peripheral,
                characteristic,
                byteArrayOf(1),
                CharacteristicWriteType.WithoutResponse,
            )
        }
        runCurrent()

        assertFalse(sending.isCompleted)
        assertEquals(CommandQueuePhase.Backpressured, queue.state.value.commands.single().phase)
        assertEquals(1, attempts)

        engine.capabilities.value = mapOf(
            key to CharacteristicWriteCapability(20, ready = true, supported = true),
        )
        runCurrent()

        assertEquals(CommandQueueResult.Sent, sending.await())
        assertEquals(2, attempts)
        queue.close()
    }

    @Test
    fun unknownReadinessMakesBackpressureTerminal() = runTest {
        val engine = FakeEngine(backgroundScope)
        val queue = installedQueue(engine)
        engine.onWrite = { _, _, _, _ -> CharacteristicWriteResult.Backpressured }

        val result = queue.send(
            FakePeripheral("one"),
            FakeCharacteristic(),
            byteArrayOf(1),
            CharacteristicWriteType.WithoutResponse,
        )

        assertEquals(CommandQueueResult.Backpressured, result)
        queue.close()
    }

    @Test
    fun rejectsNewestCommandAtConfiguredItemLimit() = runTest {
        val engine = FakeEngine(backgroundScope)
        val queue = installedQueue(engine) { maxPendingItemsPerPeripheral = 1 }
        val peripheral = FakePeripheral("one")
        val characteristic = FakeCharacteristic()
        val mayFinish = CompletableDeferred<Unit>()
        engine.onWrite = { _, _, _, _ ->
            mayFinish.await()
            CharacteristicWriteResult.Sent
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(peripheral, characteristic, byteArrayOf(1))
        }
        runCurrent()

        assertEquals(
            CommandQueueResult.QueueFull,
            queue.send(peripheral, characteristic, byteArrayOf(2)),
        )
        mayFinish.complete(Unit)
        runCurrent()
        assertEquals(CommandQueueResult.Sent, first.await())
        queue.close()
    }

    @Test
    fun globalByteLimitAppliesAcrossPeripherals() = runTest {
        val engine = FakeEngine(backgroundScope)
        val queue = installedQueue(engine) { maxPendingBytes = 2 }
        val characteristic = FakeCharacteristic()
        val mayFinish = CompletableDeferred<Unit>()
        engine.onWrite = { _, _, _, _ ->
            mayFinish.await()
            CharacteristicWriteResult.Sent
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(FakePeripheral("one"), characteristic, byteArrayOf(1, 2))
        }
        runCurrent()

        assertEquals(
            CommandQueueResult.QueueFull,
            queue.send(FakePeripheral("two"), characteristic, byteArrayOf(3)),
        )
        assertEquals(2, queue.state.value.accountedBytes)

        mayFinish.complete(Unit)
        runCurrent()
        assertEquals(CommandQueueResult.Sent, first.await())
        queue.close()
    }

    @Test
    fun rejectsOversizedPayloadBeforeCallingEngine() = runTest {
        val engine = FakeEngine(backgroundScope)
        val queue = installedQueue(engine)
        val peripheral = FakePeripheral("one")
        val key = CharacteristicWriteKey(peripheral.uuid, CharacteristicWriteType.WithResponse)
        engine.capabilities.value = mapOf(
            key to CharacteristicWriteCapability(2, ready = true, supported = true),
        )

        val result = queue.send(
            peripheral,
            FakeCharacteristic(),
            byteArrayOf(1, 2, 3),
        )

        assertEquals(CommandQueueResult.PayloadTooLarge(2), result)
        assertEquals(0, engine.writeCount)
        queue.close()
    }

    @Test
    fun disconnectCompletesAllCommandsForThatPeripheral() = runTest {
        val engine = FakeEngine(backgroundScope)
        val queue = installedQueue(engine)
        val peripheral = FakePeripheral("one")
        val characteristic = FakeCharacteristic()
        val never = CompletableDeferred<Unit>()
        engine.onWrite = { _, _, _, _ ->
            never.await()
            CharacteristicWriteResult.Sent
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(peripheral, characteristic, byteArrayOf(1))
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(peripheral, characteristic, byteArrayOf(2))
        }
        runCurrent()

        engine.connectionUpdates.emit(
            ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Disconnected),
        )
        runCurrent()

        assertEquals(CommandQueueResult.Disconnected, first.await())
        assertEquals(CommandQueueResult.Disconnected, second.await())
        assertTrue(queue.state.value.commands.isEmpty())
        queue.close()
    }

    @Test
    fun cancellingQueuedCallerRemovesItsCommand() = runTest {
        val engine = FakeEngine(backgroundScope)
        val queue = installedQueue(engine)
        val peripheral = FakePeripheral("one")
        val characteristic = FakeCharacteristic()
        val firstMayFinish = CompletableDeferred<Unit>()
        engine.onWrite = { _, _, _, _ ->
            firstMayFinish.await()
            CharacteristicWriteResult.Sent
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(peripheral, characteristic, byteArrayOf(1))
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(peripheral, characteristic, byteArrayOf(2))
        }
        runCurrent()

        second.cancel()
        runCurrent()
        assertEquals(1, queue.state.value.commands.size)

        firstMayFinish.complete(Unit)
        runCurrent()
        assertEquals(CommandQueueResult.Sent, first.await())
        assertEquals(1, engine.writeCount)
        queue.close()
    }

    @Test
    fun cancellingBackpressuredHeadAllowsNextCommandToRun() = runTest {
        val engine = FakeEngine(backgroundScope)
        val queue = installedQueue(engine)
        val peripheral = FakePeripheral("one")
        val characteristic = FakeCharacteristic()
        val key = CharacteristicWriteKey(
            peripheral.uuid,
            CharacteristicWriteType.WithoutResponse,
        )
        engine.capabilities.value = mapOf(
            key to CharacteristicWriteCapability(20, ready = true, supported = true),
        )
        var attempts = 0
        engine.onWrite = { _, _, _, _ ->
            attempts++
            if (attempts == 1) {
                engine.capabilities.value = mapOf(
                    key to CharacteristicWriteCapability(20, ready = false, supported = true),
                )
                CharacteristicWriteResult.Backpressured
            } else {
                CharacteristicWriteResult.Sent
            }
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(
                peripheral,
                characteristic,
                byteArrayOf(1),
                CharacteristicWriteType.WithoutResponse,
            )
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(
                peripheral,
                characteristic,
                byteArrayOf(2),
                CharacteristicWriteType.WithoutResponse,
            )
        }
        runCurrent()

        first.cancel()
        runCurrent()

        engine.capabilities.value = mapOf(
            key to CharacteristicWriteCapability(20, ready = true, supported = true),
        )
        runCurrent()

        assertEquals(CommandQueueResult.Sent, second.await())
        assertEquals(2, attempts)
        queue.close()
    }

    @Test
    fun disconnectedWriteCompletesTheRestOfThatPeripheralQueue() = runTest {
        val engine = FakeEngine(backgroundScope)
        val queue = installedQueue(engine)
        val peripheral = FakePeripheral("one")
        val characteristic = FakeCharacteristic()
        engine.onWrite = { _, _, _, _ -> CharacteristicWriteResult.Disconnected }

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(peripheral, characteristic, byteArrayOf(1))
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(peripheral, characteristic, byteArrayOf(2))
        }
        runCurrent()

        assertEquals(CommandQueueResult.Disconnected, first.await())
        assertEquals(CommandQueueResult.Disconnected, second.await())
        assertEquals(1, engine.writeCount)
        queue.close()
    }

    @Test
    fun closeCompletesOutstandingCommands() = runTest {
        val engine = FakeEngine(backgroundScope)
        val queue = installedQueue(engine)
        val never = CompletableDeferred<Unit>()
        engine.onWrite = { _, _, _, _ ->
            never.await()
            CharacteristicWriteResult.Sent
        }
        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(FakePeripheral("one"), FakeCharacteristic(), byteArrayOf(1))
        }
        runCurrent()

        queue.close()
        runCurrent()

        assertEquals(CommandQueueResult.Closed, sending.await())
        assertTrue(queue.state.value.commands.isEmpty())
    }

    private fun installedQueue(
        engine: FakeEngine,
        configure: CommandQueuePlugin.Config.() -> Unit = {},
    ): CommandQueuePlugin {
        val queue = CommandQueuePlugin.create(configure)
        BlueFalcon(engine).plugins.install(queue)
        return queue
    }
}

private class FakeEngine(
    override val scope: CoroutineScope,
) : BlueFalconEngine {
    override val peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val managerState = MutableStateFlow(BluetoothManagerState.Ready)
    override val characteristicNotifications = MutableSharedFlow<CharacteristicNotification>()
    val connectionUpdates = MutableSharedFlow<ConnectionStateUpdate>(extraBufferCapacity = 16)
    override val connectionStateUpdates: SharedFlow<ConnectionStateUpdate> =
        connectionUpdates.asSharedFlow()
    override val serviceDiscoveryUpdates = MutableSharedFlow<ServiceDiscoveryUpdate>()
    val capabilities = MutableStateFlow<Map<CharacteristicWriteKey, CharacteristicWriteCapability>>(
        emptyMap(),
    )
    override val characteristicWriteCapabilities:
        StateFlow<Map<CharacteristicWriteKey, CharacteristicWriteCapability>> = capabilities
    override val centralCapabilities = CentralCapabilities(
        reliableWriteResults = true,
        writeWithoutResponseReadiness = true,
        perConnectionMaximumWriteLength = true,
        notificationSubscriptionResults = true,
        restoration = false,
    )
    override val isScanning = false

    val values = mutableListOf<ByteArray>()
    val operations = mutableListOf<String>()
    var writeCount = 0
    var readValue: ByteArray? = null
    var subscriptionResult: NotificationSubscriptionResult =
        NotificationSubscriptionResult.Unsupported
    var emitDiscoveryUpdates = true
    var onWrite:
        suspend (BluetoothPeripheral, BluetoothCharacteristic, ByteArray, CharacteristicWriteType) ->
            CharacteristicWriteResult = { _, _, _, _ -> CharacteristicWriteResult.Sent }

    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: CharacteristicWriteType,
    ): CharacteristicWriteResult {
        writeCount++
        operations += "write"
        return onWrite(peripheral, characteristic, value, writeType)
    }

    override suspend fun scan(filters: List<ServiceFilter>) = Unit
    override suspend fun stopScanning() = Unit
    override fun clearPeripherals() = Unit
    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) = Unit
    override suspend fun disconnect(peripheral: BluetoothPeripheral) = Unit
    override fun connectionState(peripheral: BluetoothPeripheral) = BluetoothPeripheralState.Connected
    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? = null
    override fun requestConnectionPriority(
        peripheral: BluetoothPeripheral,
        priority: ConnectionPriority,
    ) = Unit
    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {
        operations += "discover-services"
        if (emitDiscoveryUpdates) {
            serviceDiscoveryUpdates.emit(
                ServiceDiscoveryUpdate(
                    peripheral,
                    dev.bluefalcon.core.ServiceDiscoveryPhase.ServicesDiscovered,
                ),
            )
        }
    }
    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>,
    ) {
        operations += "discover-characteristics"
        if (emitDiscoveryUpdates) {
            serviceDiscoveryUpdates.emit(
                ServiceDiscoveryUpdate(
                    peripheral,
                    dev.bluefalcon.core.ServiceDiscoveryPhase.CharacteristicsDiscovered,
                    service,
                ),
            )
        }
    }
    override suspend fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
    ): ByteArray? {
        operations += "read"
        return readValue?.copyOf()
    }
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?,
    ) = Unit
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?,
    ) = Unit
    override suspend fun setNotificationSubscription(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        enabled: Boolean,
    ): NotificationSubscriptionResult {
        operations += "subscription"
        return subscriptionResult
    }
    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean,
    ) = Unit
    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean,
    ) = Unit
    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor,
    ) = Unit
    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray,
    ) = Unit
    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {
        operations += "change-mtu"
    }
    override fun refreshGattCache(peripheral: BluetoothPeripheral) = false
    override suspend fun openL2capChannel(
        peripheral: BluetoothPeripheral,
        psm: Int,
        secure: Boolean,
    ): BluetoothSocket = error("Not used")
    override suspend fun createBond(peripheral: BluetoothPeripheral) = Unit
    override suspend fun removeBond(peripheral: BluetoothPeripheral) = Unit
    override suspend fun selectAdapter(identifier: String) = AdapterSelectionResult.Unsupported
}

private data class FakePeripheral(
    override val uuid: String,
) : BluetoothPeripheral {
    override val name: String = uuid
    override val rssi: Float? = null
    override val mtuSize: Int? = 23
    override val services: List<BluetoothService> = emptyList()
    override val characteristics: List<BluetoothCharacteristic> = emptyList()
}

private class FakeCharacteristic : BluetoothCharacteristic {
    override val uuid = "00002a00-0000-1000-8000-00805f9b34fb".toUuid()
    override val name: String? = null
    override val value: ByteArray? = null
    override val notifications = MutableSharedFlow<ByteArray>()
    override val descriptors: List<BluetoothCharacteristicDescriptor> = emptyList()
    override val isNotifying = false
    override val service: BluetoothService? = null
}

private data class FakeService(
    override val uuid: Uuid = "00001800-0000-1000-8000-00805f9b34fb".toUuid(),
) : BluetoothService {
    override val name: String? = null
    override val characteristics: List<BluetoothCharacteristic> = emptyList()
}
