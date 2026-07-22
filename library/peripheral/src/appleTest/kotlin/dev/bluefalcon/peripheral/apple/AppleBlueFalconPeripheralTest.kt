package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralManagerState
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppleBlueFalconPeripheralTest {

    @Test
    fun factoryExposesAppleCapabilitiesAndDrivesStackLifecycle() = runTest {
        val stack = FakeApplePeripheralStack()
        val peripheral = createBlueFalconPeripheral(
            stack = stack,
            logger = null,
            coroutineContext = EmptyCoroutineContext,
        )
        val config = PeripheralConfig(AdvertiseConfig(localName = "blue-falcon"))

        assertTrue(peripheral.capabilities.localGattServer)
        assertTrue(peripheral.capabilities.stateRestoration)
        peripheral.start(config)
        assertEquals(PeripheralManagerState.Running, peripheral.state.value)
        assertEquals(listOf(config), stack.openConfigs)

        peripheral.stop()
        assertEquals(PeripheralManagerState.Stopped, peripheral.state.value)
        assertEquals(1, stack.stopAdvertisingCalls)
        assertEquals(1, stack.clearServicesCalls)

        peripheral.close()
        assertEquals(PeripheralManagerState.Closed, peripheral.state.value)
        assertEquals(1, stack.closeCalls)
    }
}
