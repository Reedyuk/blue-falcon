package dev.bluefalcon.core

import dev.bluefalcon.core.mocks.FakeBlueFalconEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

/**
 * Tests for the structured per-peripheral connection state machine (ADR 0008).
 */
class PeripheralConnectionStateTest {

    @Test
    fun `never-connected peripheral reports Disconnected with no reason`() = runTest {
        val engine = FakeBlueFalconEngine()
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)

        val state = blueFalcon.peripheralState(peripheral)

        assertEquals(PeripheralConnectionState.Disconnected(null), state)
    }

    @Test
    fun `connect sets Connecting immediately`() = runTest {
        val engine = FakeBlueFalconEngine().apply {
            onConnect = {} // don't emit Connected synchronously; simulate async platform callback
        }
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)

        blueFalcon.connect(peripheral)

        assertEquals(PeripheralConnectionState.Connecting, blueFalcon.peripheralState(peripheral))
    }

    @Test
    fun `synchronous connect failure transitions directly to Disconnected with ConnectFailed`() = runTest {
        val engine = FakeBlueFalconEngine().apply {
            shouldFailConnect = true
        }
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)

        blueFalcon.connect(peripheral)

        val state = blueFalcon.peripheralState(peripheral)
        val disconnected = assertIs<PeripheralConnectionState.Disconnected>(state)
        assertIs<DisconnectReason.ConnectFailed>(disconnected.reason)
    }

    @Test
    fun `Connected event transitions Connecting to Connected`() = runTest {
        val engine = FakeBlueFalconEngine()
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)

        blueFalcon.connect(peripheral)
        engine.emitConnectionStateUpdate(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Connected))

        assertEquals(PeripheralConnectionState.Connected, blueFalcon.peripheralState(peripheral))
    }

    @Test
    fun `ServicesDiscovered after Connected transitions to Ready`() = runTest {
        val engine = FakeBlueFalconEngine()
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)

        blueFalcon.connect(peripheral)
        engine.emitConnectionStateUpdate(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Connected))
        engine.emitServiceDiscoveryUpdate(
            ServiceDiscoveryUpdate(peripheral, ServiceDiscoveryPhase.ServicesDiscovered)
        )

        assertEquals(PeripheralConnectionState.Ready, blueFalcon.peripheralState(peripheral))
    }

    @Test
    fun `ServicesDiscovered is ignored when not currently Connected`() = runTest {
        val engine = FakeBlueFalconEngine()
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)

        // No connect() call at all - peripheral is Disconnected.
        engine.emitServiceDiscoveryUpdate(
            ServiceDiscoveryUpdate(peripheral, ServiceDiscoveryPhase.ServicesDiscovered)
        )

        assertEquals(PeripheralConnectionState.Disconnected(null), blueFalcon.peripheralState(peripheral))
    }

    @Test
    fun `CharacteristicsDiscovered does not change state`() = runTest {
        val engine = FakeBlueFalconEngine()
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)

        blueFalcon.connect(peripheral)
        engine.emitConnectionStateUpdate(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Connected))
        engine.emitServiceDiscoveryUpdate(
            ServiceDiscoveryUpdate(peripheral, ServiceDiscoveryPhase.CharacteristicsDiscovered)
        )

        // Still Connected, not Ready, since only characteristics (not services) were reported.
        assertEquals(PeripheralConnectionState.Connected, blueFalcon.peripheralState(peripheral))
    }

    @Test
    fun `disconnect sets Disconnecting then UserInitiated on completion`() = runTest {
        val engine = FakeBlueFalconEngine()
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)

        blueFalcon.connect(peripheral)
        engine.emitConnectionStateUpdate(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Connected))
        blueFalcon.disconnect(peripheral)
        assertEquals(PeripheralConnectionState.Disconnecting, blueFalcon.peripheralState(peripheral))

        engine.emitConnectionStateUpdate(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Disconnected))

        val state = blueFalcon.peripheralState(peripheral)
        val disconnected = assertIs<PeripheralConnectionState.Disconnected>(state)
        assertSame(DisconnectReason.UserInitiated, disconnected.reason)
    }

    @Test
    fun `unexpected drop while Connected reports Unexpected reason`() = runTest {
        val engine = FakeBlueFalconEngine()
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)

        blueFalcon.connect(peripheral)
        engine.emitConnectionStateUpdate(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Connected))
        // No disconnect() call - the peripheral just drops.
        engine.emitConnectionStateUpdate(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Disconnected))

        val state = blueFalcon.peripheralState(peripheral)
        val disconnected = assertIs<PeripheralConnectionState.Disconnected>(state)
        assertSame(DisconnectReason.Unexpected, disconnected.reason)
    }

    @Test
    fun `unexpected drop while Ready reports Unexpected reason`() = runTest {
        val engine = FakeBlueFalconEngine()
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)

        blueFalcon.connect(peripheral)
        engine.emitConnectionStateUpdate(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Connected))
        engine.emitServiceDiscoveryUpdate(
            ServiceDiscoveryUpdate(peripheral, ServiceDiscoveryPhase.ServicesDiscovered)
        )
        engine.emitConnectionStateUpdate(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Disconnected))

        val state = blueFalcon.peripheralState(peripheral)
        val disconnected = assertIs<PeripheralConnectionState.Disconnected>(state)
        assertSame(DisconnectReason.Unexpected, disconnected.reason)
    }

    @Test
    fun `connectionStateFlow reflects current value immediately without a prior event`() = runTest {
        val engine = FakeBlueFalconEngine()
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)

        blueFalcon.connect(peripheral)

        // Subscribing "late" (after connect() already ran) still observes Connecting immediately,
        // since connectionStateFlow is backed by a StateFlow rather than a SharedFlow.
        assertEquals(PeripheralConnectionState.Connecting, blueFalcon.connectionStateFlow(peripheral).value)
    }

    @Test
    fun `connectionStates map only contains peripherals that have been touched`() = runTest {
        val engine = FakeBlueFalconEngine()
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)

        assertNull(blueFalcon.connectionStates.value[peripheral.uuid])

        blueFalcon.connect(peripheral)

        assertEquals(PeripheralConnectionState.Connecting, blueFalcon.connectionStates.value[peripheral.uuid])
    }
}
