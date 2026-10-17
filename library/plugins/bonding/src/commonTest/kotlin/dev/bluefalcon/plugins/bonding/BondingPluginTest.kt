package dev.bluefalcon.plugins.bonding

import dev.bluefalcon.core.*
import dev.bluefalcon.core.plugin.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Minimal fake engine for bonding plugin tests.
 */
private class FakeEngine(
    val bondCapability: BondCapability = BondCapability.Unsupported,
) : BlueFalconEngine {
    override val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = MutableStateFlow(emptySet())
    override val managerState: StateFlow<BluetoothManagerState> = MutableStateFlow(BluetoothManagerState.Ready)

    private val _charNotifications = MutableSharedFlow<CharacteristicNotification>(extraBufferCapacity = 64)
    override val characteristicNotifications: SharedFlow<CharacteristicNotification> = _charNotifications

    private val _bondStateUpdates = MutableSharedFlow<BondStateUpdate>(extraBufferCapacity = 64)
    override val bondStateUpdates: SharedFlow<BondStateUpdate> = _bondStateUpdates

    override val centralCapabilities = CentralCapabilities(
        reliableWriteResults = false,
        writeWithoutResponseReadiness = false,
        perConnectionMaximumWriteLength = false,
        notificationSubscriptionResults = false,
        restoration = false,
        bondCapability = bondCapability,
    )

    override var isScanning: Boolean = false
    var createBondCalled = false
    var removeBondCalled = false
    var emitBondStateDuringCreate = false
    var emitBondStateDuringRemove = false
    var suspendCreateBond = false
    var suspendRemoveBond = false

    override suspend fun scan(filters: List<ServiceFilter>) {}
    override suspend fun stopScanning() {}
    override fun clearPeripherals() {}
    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {}
    override suspend fun disconnect(peripheral: BluetoothPeripheral) {}
    override fun connectionState(peripheral: BluetoothPeripheral) = BluetoothPeripheralState.Disconnected
    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? = null
    override fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {}
    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {}
    override suspend fun discoverCharacteristics(peripheral: BluetoothPeripheral, service: BluetoothService, characteristicUUIDs: List<Uuid>) {}
    override suspend fun readCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic): ByteArray? = null
    override suspend fun writeCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic, value: String, writeType: Int?) {}
    override suspend fun writeCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic, value: ByteArray, writeType: Int?) {}
    override suspend fun notifyCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic, notify: Boolean) {}
    override suspend fun indicateCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic, indicate: Boolean) {}
    override suspend fun readDescriptor(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic, descriptor: BluetoothCharacteristicDescriptor) {}
    override suspend fun writeDescriptor(peripheral: BluetoothPeripheral, descriptor: BluetoothCharacteristicDescriptor, value: ByteArray) {}
    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {}
    override fun refreshGattCache(peripheral: BluetoothPeripheral) = false
    override suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int, secure: Boolean): BluetoothSocket {
        throw UnsupportedOperationException()
    }
    override suspend fun createBond(peripheral: BluetoothPeripheral) {
        createBondCalled = true
        if (suspendCreateBond) awaitCancellation()
        if (emitBondStateDuringCreate) {
            emitBondState(BondStateUpdate(peripheral.uuid, BlueFalconBondState.Bonded))
        }
    }
    override suspend fun removeBond(peripheral: BluetoothPeripheral) {
        removeBondCalled = true
        if (suspendRemoveBond) awaitCancellation()
        if (emitBondStateDuringRemove) {
            emitBondState(BondStateUpdate(peripheral.uuid, BlueFalconBondState.None))
        }
    }

    suspend fun emitBondState(update: BondStateUpdate) { _bondStateUpdates.emit(update) }
}

private class FakePeripheral(val id: String) : BluetoothPeripheral {
    override val uuid: String = id
    override val name: String? = "Fake-$id"
    override val rssi: Float? = null
    override val services: List<BluetoothService> = emptyList()
    override val manufacturerData: Map<Int, ByteArray> = emptyMap()
    override val mtuSize: Int? = null
    override val characteristics: List<BluetoothCharacteristic> = emptyList()
}

@OptIn(ExperimentalCoroutinesApi::class)
class BondingPluginTest {

    @Test
    fun `requestBond returns Unsupported when capability is Unsupported`() = runTest {
        val engine = FakeEngine(BondCapability.Unsupported)
        val falcon = BlueFalcon(engine)
        val plugin = BondingPlugin.create()
        plugin.bind(falcon)

        val result = plugin.requestBond(FakePeripheral("abc"))
        assertIs<BondResult.Unsupported>(result)
    }

    @Test
    fun `requestBond returns Unsupported when capability is Implicit`() = runTest {
        val engine = FakeEngine(BondCapability.Implicit)
        val falcon = BlueFalcon(engine)
        val plugin = BondingPlugin.create()
        plugin.bind(falcon)

        val result = plugin.requestBond(FakePeripheral("abc"))
        assertIs<BondResult.Unsupported>(result)
    }

    @Test
    fun `requestBond returns Bonded when bond state update arrives`() = runTest {
        val engine = FakeEngine(BondCapability.Supported)
        val falcon = BlueFalcon(engine)
        val plugin = BondingPlugin.create { bondTimeout = 5000.milliseconds }
        plugin.bind(falcon)

        val peripheral = FakePeripheral("device-1")

        // Emit the bond state update shortly after requestBond is called
        launch {
            engine.emitBondState(BondStateUpdate("device-1", BlueFalconBondState.Bonding))
            engine.emitBondState(BondStateUpdate("device-1", BlueFalconBondState.Bonded))
        }

        val result = plugin.requestBond(peripheral)
        assertIs<BondResult.Bonded>(result)
        assertEquals("device-1", result.peripheralUuid)
        assertTrue(engine.createBondCalled)
    }

    @Test
    fun `requestBond observes a state update emitted before createBond returns`() = runTest {
        val engine = FakeEngine(BondCapability.Supported).apply {
            emitBondStateDuringCreate = true
        }
        val falcon = BlueFalcon(engine)
        val plugin = BondingPlugin.create { bondTimeout = 5000.milliseconds }
        plugin.bind(falcon)

        val result = plugin.requestBond(FakePeripheral("device-1"))

        assertIs<BondResult.Bonded>(result)
    }

    @Test
    fun `requestBond returns TimedOut when no state update arrives`() = runTest {
        val engine = FakeEngine(BondCapability.Supported)
        val falcon = BlueFalcon(engine)
        val plugin = BondingPlugin.create { bondTimeout = 50.milliseconds }
        plugin.bind(falcon)

        val result = plugin.requestBond(FakePeripheral("device-1"))
        assertIs<BondResult.TimedOut>(result)
    }

    @Test
    fun `requestBond times out while createBond is still running`() = runTest {
        val engine = FakeEngine(BondCapability.Supported).apply {
            suspendCreateBond = true
        }
        val falcon = BlueFalcon(engine)
        val plugin = BondingPlugin.create { bondTimeout = 50.milliseconds }
        plugin.bind(falcon)

        val request = async { plugin.requestBond(FakePeripheral("device-1")) }
        runCurrent()
        advanceTimeBy(50)
        runCurrent()

        assertTrue(engine.createBondCalled)
        assertIs<BondResult.TimedOut>(request.await())
    }

    @Test
    fun `requestUnbond returns Unsupported on non-supported platforms`() = runTest {
        val engine = FakeEngine(BondCapability.Implicit)
        val falcon = BlueFalcon(engine)
        val plugin = BondingPlugin.create()
        plugin.bind(falcon)

        val result = plugin.requestUnbond(FakePeripheral("abc"))
        assertIs<BondResult.Unsupported>(result)
    }

    @Test
    fun `requestUnbond returns Unbonded on success`() = runTest {
        val engine = FakeEngine(BondCapability.Supported)
        val falcon = BlueFalcon(engine)
        val plugin = BondingPlugin.create { bondTimeout = 5000.milliseconds }
        plugin.bind(falcon)

        val peripheral = FakePeripheral("device-1")

        launch {
            engine.emitBondState(BondStateUpdate("device-1", BlueFalconBondState.None))
        }

        val result = plugin.requestUnbond(peripheral)
        assertIs<BondResult.Unbonded>(result)
        assertTrue(engine.removeBondCalled)
    }

    @Test
    fun `requestUnbond times out while removeBond is still running`() = runTest {
        val engine = FakeEngine(BondCapability.Supported).apply {
            suspendRemoveBond = true
        }
        val falcon = BlueFalcon(engine)
        val plugin = BondingPlugin.create { bondTimeout = 50.milliseconds }
        plugin.bind(falcon)

        val request = async { plugin.requestUnbond(FakePeripheral("device-1")) }
        runCurrent()
        advanceTimeBy(50)
        runCurrent()

        assertTrue(engine.removeBondCalled)
        assertIs<BondResult.TimedOut>(request.await())
    }

    @Test
    fun `requestUnbond observes a state update emitted before removeBond returns`() = runTest {
        val engine = FakeEngine(BondCapability.Supported).apply {
            emitBondStateDuringRemove = true
        }
        val falcon = BlueFalcon(engine)
        val plugin = BondingPlugin.create { bondTimeout = 5000.milliseconds }
        plugin.bind(falcon)

        val result = plugin.requestUnbond(FakePeripheral("device-1"))

        assertIs<BondResult.Unbonded>(result)
    }

    @Test
    fun `bondStates flow is updated when bond state updates arrive`() = runTest {
        val engine = FakeEngine(BondCapability.Supported)
        val falcon = BlueFalcon(engine)
        val plugin = BondingPlugin.create()
        plugin.bind(falcon)

        assertTrue(plugin.bondStates.value.isEmpty())

        engine.emitBondState(BondStateUpdate("device-1", BlueFalconBondState.Bonded))
        advanceUntilIdle()
        val states = plugin.bondStates.value
        assertEquals(1, states.size)
        assertEquals(BlueFalconBondState.Bonded, states["device-1"]?.state)
        assertEquals(BondCapability.Supported, states["device-1"]?.capability)
    }

    @Test
    fun `requestBond returns Failed when bond attempt fails`() = runTest {
        val engine = FakeEngine(BondCapability.Supported)
        val falcon = BlueFalcon(engine)
        val plugin = BondingPlugin.create { bondTimeout = 5000.milliseconds }
        plugin.bind(falcon)

        val peripheral = FakePeripheral("device-1")

        // Bond state goes back to None (failure)
        launch {
            engine.emitBondState(BondStateUpdate("device-1", BlueFalconBondState.None))
        }

        val result = plugin.requestBond(peripheral)
        assertIs<BondResult.Failed>(result)
    }
}
